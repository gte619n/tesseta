#!/usr/bin/env bash
set -euo pipefail

# Seeds a rich, realistic workout history for the redesigned Workouts tab
# (IMPL-WEB-WORKOUT-01) against a locally-booted stack (infra/scripts/demo-workouts.sh).
# Populates: exercise catalog, a weekly streak target, an ACTIVE 4-day program,
# and ~12 weeks of completed sessions with progressive weight+reps+RIR — so the
# streak, weekly-volume bars, consistency heatmap, recent PRs, e1RM trend, and
# latest-workouts list all light up. Idempotent-ish: re-running appends another
# program; wipe the emulator (restart the stack) for a clean slate.

BACKEND="${BACKEND:-http://localhost:8080}"
USER_ID="${DEMO_USER:-demo}"
WEEKS="${DEMO_WEEKS:-12}"
# Firestore emulator (used only to resolve catalog exercise ids by name, since
# GET /api/exercises filters unenriched seeds and dev-login isn't admin).
EMULATOR="${EMULATOR:-http://127.0.0.1:8081}"
EMU_PROJECT="${EMU_PROJECT:-demo-workouts}"

require() { command -v "$1" >/dev/null 2>&1 || { echo "Missing tool: $1" >&2; exit 1; }; }
require curl; require jq; require date

devlogin() {
  curl -sf -X POST "$BACKEND/api/auth/dev-login" -H 'Content-Type: application/json' -d "$1" | jq -r .accessToken
}

echo "==> dev-login (demo user '$USER_ID')"
TOKEN=$(devlogin "{\"userId\":\"$USER_ID\",\"email\":\"demo@demo.local\",\"name\":\"Demo Lifter\"}")
AUTH=(-H "Authorization: Bearer $TOKEN")

# Resolve catalog exercise ids (Firestore doc ids) by name from the emulator, so
# logged sets reference real catalog exercises → the stats service resolves each
# one's movement pattern (findByIds), which powers chartDefaultLifts + pattern
# balance. Falls back to the readable name when the catalog isn't seeded (then
# those two cards degrade to empty states, but everything else still works).
catalog_id() {  # name -> doc id (or the name itself if not found)
  local name="$1" id
  id=$(curl -s "$EMULATOR/v1/projects/$EMU_PROJECT/databases/(default)/documents/exercises?pageSize=200" 2>/dev/null \
    | jq -r --arg n "$name" '.documents[]? | select(.fields.name.stringValue==$n)
        | (.name | sub(".*/documents/exercises/";""))' | head -1)
  [[ -n "$id" && "$id" != "null" ]] && echo "$id" || echo "$name"
}
BENCH=$(catalog_id "Barbell Bench Press")
SQUAT=$(catalog_id "Barbell Back Squat")
RDL=$(catalog_id "Romanian Deadlift")
OHP=$(catalog_id "Overhead Press")
ROW=$(catalog_id "Bent-Over Barbell Row")
PULLUP=$(catalog_id "Pull-Up")
echo "   exercise ids: bench=$BENCH squat=$SQUAT rdl=$RDL ohp=$OHP row=$ROW pull=$PULLUP"

# progression params per exercise: base weight + weekly step. macOS ships bash
# 3.2 (no associative arrays), so these are case lookups keyed by exercise id.
base_for() { case "$1" in
  "$BENCH") echo 135;; "$OHP") echo 75;; "$ROW") echo 115;;
  "$SQUAT") echo 185;; "$RDL") echo 155;; "$PULLUP") echo 25;; *) echo 100;; esac; }
step_for() { case "$1" in
  "$SQUAT"|"$RDL") echo 10;; *) echo 5;; esac; }
off_for() { case "$1" in
  MON) echo 0;; TUE) echo 1;; WED) echo 2;; THU) echo 3;;
  FRI) echo 4;; SAT) echo 5;; SUN) echo 6;; *) echo 0;; esac; }

echo "==> Setting weekly streak target = 4"
curl -sf -X PUT "$BACKEND/api/me/workout-programs/settings" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -d '{"weeklyStreakTarget":4}' >/dev/null

# ---- build the program (one block per day, 2 prescriptions each) ----
day_json() {  # label dayOfWeek exId1 exId2
  jq -n --arg label "$1" --arg dow "$2" --arg e1 "$3" --arg e2 "$4" '
    { label:$label, dayOfWeek:$dow, locationId:null, blocks:[
      { type:"MAIN", title:"Main", prescriptions:[
        { exerciseId:$e1, sets:3, repsMin:5, repsMax:8, durationSeconds:null, intensity:null,
          targetWeightLbs:null, loadBasis:null, restSeconds:150, tempo:null, notes:null, deloadModifier:null },
        { exerciseId:$e2, sets:3, repsMin:5, repsMax:8, durationSeconds:null, intensity:null,
          targetWeightLbs:null, loadBasis:null, restSeconds:150, tempo:null, notes:null, deloadModifier:null }
      ]}]}'
}
START=$(date -v-"$((WEEKS))"w +%F)

PHASES=$(jq -n \
  --argjson push "$(day_json Push MON "$BENCH" "$OHP")" \
  --argjson pull "$(day_json Pull TUE "$ROW" "$PULLUP")" \
  --argjson legs "$(day_json Legs THU "$SQUAT" "$RDL")" \
  --argjson upper "$(day_json Upper FRI "$BENCH" "$ROW")" \
  --argjson weeks "$WEEKS" \
  '[ { title:"Foundation", focus:"Hypertrophy & Strength", weeks:$weeks, deloadWeekIndex:null,
       days:[$push,$pull,$legs,$upper], nutritionGuidance:null } ]')

PROG=$(jq -n --argjson phases "$PHASES" --arg start "$START" \
  '{ title:"Push/Pull/Legs Strength Block", description:"A 4-day PPL + upper block.",
     goalId:null, schedule:{ trainingDays:["MON","TUE","THU","FRI"], dayLocations:{} },
     startDate:$start, source:"MANUAL", phases:$phases, nutritionGuidance:null }')

echo "==> Creating program"
RESP=$(curl -sf -X POST "$BACKEND/api/me/workout-programs" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -d "$PROG")
PROGRAM_ID=$(echo "$RESP" | jq -r .programId)
PHASE_ID=$(echo "$RESP" | jq -r '.phases[0].phaseId')
echo "   programId=$PROGRAM_ID phaseId=$PHASE_ID"

echo "==> Activating program"
curl -sf -X PATCH "$BACKEND/api/me/workout-programs/$PROGRAM_ID" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -d '{"status":"ACTIVE"}' >/dev/null

# Monday of the current week
DOW=$(date +%u)
MON_THIS=$(date -v-"$((DOW-1))"d +%F)

# per-day (dayId, dayOfWeek, blockId, [orderIndex,exerciseId]...) from the deep response
DAYS=$(echo "$RESP" | jq -c '.phases[0].days[]')

build_sets() {  # exerciseId n(1..) date  -> JSON sets array
  local ex="$1" n="$2" d="$3"
  local base step; base=$(base_for "$ex"); step=$(step_for "$ex")
  local weight=$(( base + (n-1)*step ))
  local reps=$(( 5 + (n-1)%4 ))
  local top=$(( reps > 5 ? reps-1 : reps ))
  # Stamp each set's completedAt (the progression engine keys its per-set
  # observations off this; null timestamps are skipped, which would leave the
  # pattern-balance week-review empty).
  jq -n --argjson w "$weight" --argjson r "$reps" --argjson t "$top" \
    --arg t1 "${d}T18:05:00Z" --arg t2 "${d}T18:12:00Z" --arg t3 "${d}T18:19:00Z" \
    '[ {weightLbs:$w,reps:$r,rir:2,rirSource:"REPORTED",restSeconds:150,completedAt:$t1},
       {weightLbs:$w,reps:$r,rir:2,rirSource:"REPORTED",restSeconds:150,completedAt:$t2},
       {weightLbs:$w,reps:$t,rir:1,rirSource:"REPORTED",restSeconds:150,completedAt:$t3} ]'
}

complete_session() {  # date dayId phaseId dayJson n feeling
  local d="$1" dayId="$2" phaseId="$3" dayJson="$4" n="$5" feeling="$6"
  local blockId; blockId=$(echo "$dayJson" | jq -r '.blocks[0].blockId')
  local logged="[]"
  while read -r pr; do
    local oi ex; oi=$(echo "$pr" | jq -r '.orderIndex'); ex=$(echo "$pr" | jq -r '.exerciseId')
    local sets; sets=$(build_sets "$ex" "$n" "$d")
    logged=$(jq -n --argjson acc "$logged" --arg b "$blockId" --argjson oi "$oi" --argjson sets "$sets" \
      '$acc + [ {blockId:$b, orderIndex:$oi, sets:$sets} ]')
  done < <(echo "$dayJson" | jq -c '.blocks[0].prescriptions[]')

  local body
  body=$(jq -n --argjson logged "$logged" --arg at "${d}T18:00:00Z" \
    --arg phase "$phaseId" --arg day "$dayId" --arg date "$d" --argjson feeling "$feeling" \
    '{ status:"COMPLETED", completedAt:$at, durationSeconds:3600, logged:$logged,
       phaseId:$phase, dayId:$day, date:$date, feeling:$feeling }')
  curl -sf -X PUT "$BACKEND/api/me/workout-programs/$PROGRAM_ID/sessions/${d}_${dayId}" \
    "${AUTH[@]}" -H 'Content-Type: application/json' -d "$body" >/dev/null
}

echo "==> Logging ~$WEEKS weeks of sessions (progressive overload)"
count=0
# oldest week first so PRs accrue chronologically; current week (w=0) partial (2/4)
for (( w=WEEKS; w>=0; w-- )); do
  n=$(( WEEKS - w + 1 ))               # 1..WEEKS+1, weight climbs each week
  wk_mon=$(date -j -v-"${w}"w -f "%Y-%m-%d" "$MON_THIS" +%F)
  feeling=$(( 3 + n%3 ))
  di=0
  while read -r dayJson; do
    di=$(( di+1 ))
    # current in-progress week: only the first two days done (2/4 → doesn't break streak)
    [[ "$w" -eq 0 && "$di" -gt 2 ]] && continue
    dayId=$(echo "$dayJson" | jq -r '.dayId')
    dow=$(echo "$dayJson" | jq -r '.dayOfWeek')
    off=$(off_for "$dow")
    d=$(date -j -v+"${off}"d -f "%Y-%m-%d" "$wk_mon" +%F)
    # don't log future dates
    [[ "$d" > "$(date +%F)" ]] && continue
    complete_session "$d" "$dayId" "$PHASE_ID" "$dayJson" "$n" "$feeling"
    count=$(( count+1 ))
  done < <(echo "$DAYS")
  printf '   week -%-2d (%s): logged\n' "$w" "$wk_mon"
done

echo "==> Done. Logged $count sessions for user '$USER_ID'."
echo "   Sign in at /auth/dev?userId=$USER_ID and open Workouts."

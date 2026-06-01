package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramService;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramValidator;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutScheduleService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/me/workout-programs")
public class WorkoutProgramController {

    private final CurrentUserProvider currentUser;
    private final WorkoutProgramService service;
    private final WorkoutScheduleService schedule;
    private final WorkoutProgramValidator validator;
    private final WorkoutProgramAssembler assembler;

    public WorkoutProgramController(
        CurrentUserProvider currentUser,
        WorkoutProgramService service,
        WorkoutScheduleService schedule,
        WorkoutProgramValidator validator,
        WorkoutProgramAssembler assembler
    ) {
        this.currentUser = currentUser;
        this.service = service;
        this.schedule = schedule;
        this.validator = validator;
        this.assembler = assembler;
    }

    @GetMapping
    public List<WorkoutProgramResponse> list() {
        String userId = currentUser.get().userId();
        return service.list(userId).stream().map(WorkoutProgramResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<WorkoutProgramDeepResponse> create(@RequestBody CreateProgramRequest body) {
        String userId = currentUser.get().userId();
        WorkoutProgram input = new WorkoutProgram(
            userId, null, body.title(), body.description(), body.goalId(),
            ProgramStatus.DRAFT, body.source(), body.startDate(), body.schedule(),
            null, body.phases(), null, null, null);
        WorkoutProgram created = service.create(input);
        return ResponseEntity.status(HttpStatus.CREATED).body(assembler.deep(created));
    }

    @GetMapping("/{programId}")
    public WorkoutProgramDeepResponse getDeep(@PathVariable String programId) {
        String userId = currentUser.get().userId();
        WorkoutProgram p = service.findById(userId, programId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return assembler.deep(p);
    }

    @PatchMapping("/{programId}")
    public WorkoutProgramDeepResponse update(@PathVariable String programId, @RequestBody UpdateProgramRequest body) {
        String userId = currentUser.get().userId();
        if (service.findById(userId, programId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        WorkoutProgram updated = service.update(userId, programId, body.title(), body.description(),
            body.goalId(), body.schedule(), body.startDate(), body.status(), body.phases());
        return assembler.deep(updated);
    }

    @DeleteMapping("/{programId}")
    public ResponseEntity<Void> archive(@PathVariable String programId) {
        String userId = currentUser.get().userId();
        if (service.findById(userId, programId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        service.archive(userId, programId);
        return ResponseEntity.noContent().build();
    }

    /** Validate without persisting changes — returns the inline issue list. */
    @PostMapping("/{programId}/validate")
    public List<String> validate(@PathVariable String programId) {
        String userId = currentUser.get().userId();
        WorkoutProgram p = service.findById(userId, programId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return validator.validate(userId, p);
    }

    @PostMapping("/{programId}/activate")
    public List<ScheduledWorkoutResponse> activate(@PathVariable String programId) {
        String userId = currentUser.get().userId();
        WorkoutProgram p = service.findById(userId, programId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<String> issues = validator.validate(userId, p);
        if (!issues.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, String.join("; ", issues));
        }
        List<ScheduledWorkout> scheduled = schedule.activate(userId, programId);
        return assembler.scheduled(userId, scheduled);
    }

    @GetMapping("/{programId}/calendar")
    public List<ScheduledWorkoutResponse> calendar(
        @PathVariable String programId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        String userId = currentUser.get().userId();
        if (service.findById(userId, programId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return assembler.scheduled(userId, schedule.calendar(userId, programId, from, to));
    }
}

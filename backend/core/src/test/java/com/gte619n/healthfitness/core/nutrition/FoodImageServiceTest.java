package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit-tests {@link FoodImageService} orchestration with fake generator/storage/
 * reader ports (no Gemini, no GCS), mirroring {@link NutritionCaptureServiceTest}.
 * Confirms the {@code NONE → PENDING → READY} happy path, the empty-generator →
 * {@code FAILED} path, that the user's meal photo is fed as a visual reference,
 * and that a missing generator/storage port makes enqueue a graceful no-op
 * (the {@code ObjectProvider} seam used in core-only contexts).
 *
 * <p>{@code enqueueGeneration} dispatches the actual work via
 * {@code CompletableFuture.runAsync}; tests assert the synchronous {@code PENDING}
 * flip, then drive {@link FoodImageService#generateNow} directly for the
 * deterministic terminal-state assertions.
 */
class FoodImageServiceTest {

    private static final byte[] PNG = "png-bytes".getBytes();

    @Test
    void enqueue_flipsToPendingSynchronously_thenGenerateNowReachesReady() {
        FakeRepo repo = new FakeRepo();
        repo.add(food("f1", FoodImageStatus.NONE));
        // A generator that blocks until released keeps the dispatched async task
        // parked, so the synchronous PENDING flip is observable without a race.
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        FoodImageGenerator blocking = (f, ref, mime) -> {
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Optional.of(PNG);
        };
        FoodImageService svc = new FoodImageService(
            repo, provider(blocking), provider(new FakeStore("u")), empty());

        svc.enqueueGeneration("f1", null);
        // enqueue flips to PENDING synchronously before dispatching async work.
        assertEquals(FoodImageStatus.PENDING, repo.get("f1").imageStatus());
        release.countDown();

        // Terminal state asserted deterministically via the synchronous path.
        FakeRepo repo2 = new FakeRepo();
        repo2.add(food("f1b", FoodImageStatus.NONE));
        FakeGenerator gen = new FakeGenerator(Optional.of(PNG));
        FakeStore store = new FakeStore("https://img/f1.png");
        FoodImageService svc2 = new FoodImageService(
            repo2, provider(gen), provider(store), empty());

        svc2.generateNow("f1b", null);

        CatalogFood after = repo2.get("f1b");
        assertEquals(FoodImageStatus.READY, after.imageStatus());
        assertEquals("https://img/f1.png", after.imageUrl());
        assertArrayEquals(PNG, store.lastBytes);
        assertEquals("f1b", store.lastFoodId);
        assertEquals("f1b", gen.lastFood.foodId(), "generator receives the catalog food");
    }

    @Test
    void generate_generatorReturnsEmpty_marksFailed() {
        FakeRepo repo = new FakeRepo();
        repo.add(food("f2", FoodImageStatus.NONE));
        FakeGenerator gen = new FakeGenerator(Optional.empty());
        FakeStore store = new FakeStore("unused");

        FoodImageService svc = new FoodImageService(
            repo, provider(gen), provider(store), empty());

        svc.generateNow("f2", null);

        assertEquals(FoodImageStatus.FAILED, repo.get("f2").imageStatus());
        assertNull(repo.get("f2").imageUrl());
        assertNull(store.lastBytes, "no upload attempted when generation is empty");
    }

    @Test
    void generate_generatorThrows_marksFailed() {
        FakeRepo repo = new FakeRepo();
        repo.add(food("f3", FoodImageStatus.NONE));
        FoodImageGenerator throwing = (f, ref, mime) -> {
            throw new RuntimeException("gemini down");
        };
        FoodImageService svc = new FoodImageService(
            repo, provider(throwing), provider(new FakeStore("x")), empty());

        svc.generateNow("f3", null);

        assertEquals(FoodImageStatus.FAILED, repo.get("f3").imageStatus());
    }

    @Test
    void generate_withReferencePhoto_feedsBytesToGenerator() {
        FakeRepo repo = new FakeRepo();
        repo.add(food("f4", FoodImageStatus.NONE));
        FakeGenerator gen = new FakeGenerator(Optional.of(PNG));
        byte[] refBytes = "meal-photo".getBytes();
        MealPhotoReader reader = ref -> "ref://meal/4".equals(ref)
            ? Optional.of(new MealPhotoReader.Photo(refBytes, "image/jpeg"))
            : Optional.empty();

        FoodImageService svc = new FoodImageService(
            repo, provider(gen), provider(new FakeStore("u")), provider(reader));

        svc.generateNow("f4", "ref://meal/4");

        assertArrayEquals(refBytes, gen.lastReference, "user photo passed as reference");
        assertEquals("image/jpeg", gen.lastMime);
        assertEquals(FoodImageStatus.READY, repo.get("f4").imageStatus());
    }

    @Test
    void enqueue_withoutGeneratorPort_isNoOp() {
        FakeRepo repo = new FakeRepo();
        repo.add(food("f5", FoodImageStatus.NONE));
        FoodImageService svc = new FoodImageService(repo, empty(), empty(), empty());

        svc.enqueueGeneration("f5", null);

        assertEquals(FoodImageStatus.NONE, repo.get("f5").imageStatus(),
            "no image pipeline -> food stays NONE");
    }

    @Test
    void backfillMissing_enqueuesOnlyNoneFoods() {
        FakeRepo repo = new FakeRepo();
        repo.add(food("a", FoodImageStatus.NONE));
        repo.add(food("b", FoodImageStatus.READY));
        repo.add(food("c", FoodImageStatus.NONE));
        // Block the dispatched async tasks so the foods stay at the synchronous
        // PENDING flip — keeps the assertions free of the runAsync race.
        FoodImageGenerator blocking = (f, ref, mime) -> {
            try { Thread.sleep(60_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Optional.empty();
        };
        FoodImageService svc = new FoodImageService(
            repo, provider(blocking), provider(new FakeStore("u")), empty());

        int enqueued = svc.backfillMissing();

        assertEquals(2, enqueued);
        // Both NONE foods were flipped to PENDING; the READY one is untouched.
        assertEquals(FoodImageStatus.PENDING, repo.get("a").imageStatus());
        assertEquals(FoodImageStatus.PENDING, repo.get("c").imageStatus());
        assertEquals(FoodImageStatus.READY, repo.get("b").imageStatus());
    }

    // ---- fakes ----

    private static CatalogFood food(String id, FoodImageStatus imageStatus) {
        return new CatalogFood(
            id, "Grilled chicken", "grilled chicken", null, null, "Protein",
            new Macros(165.0, 31.0, 0.0, 3.6, 0.0, 0.0),
            List.of(new ServingSize("100 g", 100.0)), 0,
            FoodSource.GEMINI_PHOTO, null, FoodStatus.UNVERIFIED, 0, null,
            null, imageStatus, "creator", Instant.now(), Instant.now());
    }

    private static <T> ObjectProvider<T> empty() {
        return provider(null);
    }

    private static <T> ObjectProvider<T> provider(T bean) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return require(); }
            @Override public T getObject() { return require(); }
            @Override public T getIfAvailable() { return bean; }
            @Override public T getIfUnique() { return bean; }
            private T require() {
                if (bean == null) throw new IllegalStateException("no bean");
                return bean;
            }
        };
    }

    private static final class FakeGenerator implements FoodImageGenerator {
        private final Optional<byte[]> result;
        CatalogFood lastFood;
        byte[] lastReference;
        String lastMime;
        FakeGenerator(Optional<byte[]> result) { this.result = result; }
        @Override public Optional<byte[]> generate(CatalogFood food, byte[] ref, String mime) {
            this.lastFood = food;
            this.lastReference = ref;
            this.lastMime = mime;
            return result;
        }
    }

    private static final class FakeStore implements FoodImageStore {
        private final String url;
        String lastFoodId;
        byte[] lastBytes;
        FakeStore(String url) { this.url = url; }
        @Override public String upload(String foodId, byte[] imageBytes) {
            this.lastFoodId = foodId;
            this.lastBytes = imageBytes;
            return url;
        }
    }

    /** Thread-safe so the dispatched {@code runAsync} tasks can't corrupt it. */
    private static final class FakeRepo implements FoodCatalogRepository {
        private final List<CatalogFood> foods = new ArrayList<>();
        synchronized void add(CatalogFood f) { foods.add(f); }
        synchronized CatalogFood get(String id) {
            return findById(id).orElseThrow();
        }
        @Override public synchronized Optional<CatalogFood> findById(String foodId) {
            return foods.stream().filter(f -> f.foodId().equals(foodId)).findFirst();
        }
        @Override public synchronized List<CatalogFood> searchByNamePrefix(String prefixLower, int limit) {
            return List.of();
        }
        @Override public synchronized Optional<CatalogFood> findByBarcode(String code) { return Optional.empty(); }
        @Override public synchronized List<CatalogFood> findByImageStatus(FoodImageStatus status, int limit) {
            return foods.stream().filter(f -> f.imageStatus() == status).limit(limit).toList();
        }
        @Override public synchronized void save(CatalogFood food) {
            foods.removeIf(f -> f.foodId().equals(food.foodId()));
            foods.add(food);
        }
        @Override public synchronized void saveConfirmation(String foodId, String userId) {}
        @Override public synchronized int countConfirmations(String foodId) { return 0; }
    }
}

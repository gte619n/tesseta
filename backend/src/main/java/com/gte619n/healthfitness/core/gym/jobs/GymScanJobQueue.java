package com.gte619n.healthfitness.core.gym.jobs;

/** IMPL-GYM-003: durable-enqueue port for gym-video analysis jobs. */
public interface GymScanJobQueue {
    /** Durably enqueue a job for later execution. Must not run it inline. */
    void enqueue(GymScanJob job);
}

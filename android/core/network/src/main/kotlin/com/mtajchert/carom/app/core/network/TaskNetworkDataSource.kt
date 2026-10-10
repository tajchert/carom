package com.mtajchert.carom.app.core.network

import com.mtajchert.carom.app.core.network.dto.NetworkTask

/**
 * Product-independent seam demonstrated by an in-memory source.
 * Replace its Hilt binding with a real service when a backend is selected.
 * Suspend operations are awaited; implementations propagate cancellation.
 */
interface TaskNetworkDataSource {

    /**
     * Fetch all tasks from the server.
     *
     * @return List of tasks from the API
     * @throws IOException if network request fails
     */
    suspend fun getTasks(): List<NetworkTask>

    /**
     * Fetch a single task by ID.
     *
     * @param id The task ID to fetch
     * @return The task if found
     * @throws IOException if network request fails
     * @throws NoSuchElementException if the demo task is not found
     */
    suspend fun getTask(id: String): NetworkTask

    /**
     * Create a new task.
     *
     * @param task The task to create
     * @return The created task with server-assigned fields
     */
    suspend fun createTask(task: NetworkTask): NetworkTask

    /**
     * Update an existing task.
     *
     * @param task The task to update (ID must exist)
     * @return The updated task
     */
    suspend fun updateTask(task: NetworkTask): NetworkTask

    /**
     * Delete a task.
     *
     * @param id The ID of the task to delete
     */
    suspend fun deleteTask(id: String)
}

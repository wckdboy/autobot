package dev.wckdboy.autobot.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.wckdboy.autobot.core.data.model.StudioAssetRow
import dev.wckdboy.autobot.core.data.model.StudioJobRow
import dev.wckdboy.autobot.core.data.model.StudioProjectRow
import dev.wckdboy.autobot.core.data.model.StudioSnippetRow
import dev.wckdboy.autobot.core.data.model.StudioWorkflowRow
import kotlinx.coroutines.flow.Flow

@Dao
interface StudioDao {
    @Query("SELECT * FROM studio_projects ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<StudioProjectRow>>

    @Query("SELECT * FROM studio_projects ORDER BY updatedAt DESC")
    suspend fun projects(): List<StudioProjectRow>

    @Upsert
    suspend fun upsertProject(row: StudioProjectRow)

    @Query("DELETE FROM studio_projects WHERE id = :id")
    suspend fun deleteProjectRow(id: String)

    @Query("SELECT id FROM studio_assets WHERE projectId = :projectId")
    suspend fun assetIds(projectId: String): List<String>

    @Query("DELETE FROM studio_assets WHERE projectId = :projectId")
    suspend fun deleteAssetsOf(projectId: String)

    /** Removes the project and its asset rows (the caller deletes the files). */
    @Transaction
    suspend fun deleteProject(id: String) {
        deleteAssetsOf(id)
        deleteProjectRow(id)
    }

    @Query("SELECT * FROM studio_assets ORDER BY createdAt DESC")
    fun observeAssets(): Flow<List<StudioAssetRow>>

    @Query("SELECT * FROM studio_assets ORDER BY createdAt DESC")
    suspend fun assets(): List<StudioAssetRow>

    @Query("SELECT * FROM studio_assets WHERE projectId = :projectId ORDER BY createdAt DESC")
    suspend fun assetsOf(projectId: String): List<StudioAssetRow>

    @Query("SELECT * FROM studio_assets WHERE id = :id")
    suspend fun asset(id: String): StudioAssetRow?

    @Upsert
    suspend fun upsertAsset(row: StudioAssetRow)

    @Query("DELETE FROM studio_assets WHERE id = :id")
    suspend fun deleteAsset(id: String)

    @Query("SELECT * FROM studio_workflows ORDER BY name COLLATE NOCASE")
    fun observeWorkflows(): Flow<List<StudioWorkflowRow>>

    @Query("SELECT * FROM studio_workflows ORDER BY name COLLATE NOCASE")
    suspend fun workflows(): List<StudioWorkflowRow>

    @Upsert
    suspend fun upsertWorkflow(row: StudioWorkflowRow)

    @Query("DELETE FROM studio_workflows WHERE id = :id")
    suspend fun deleteWorkflow(id: String)

    @Query("SELECT * FROM studio_snippets")
    fun observeSnippets(): Flow<List<StudioSnippetRow>>

    @Query("SELECT * FROM studio_snippets")
    suspend fun snippets(): List<StudioSnippetRow>

    @Upsert
    suspend fun upsertSnippet(row: StudioSnippetRow)

    @Query("DELETE FROM studio_snippets WHERE id = :id")
    suspend fun deleteSnippet(id: String)

    @Query("SELECT * FROM studio_jobs ORDER BY createdAt")
    suspend fun jobs(): List<StudioJobRow>

    @Upsert
    suspend fun upsertJob(row: StudioJobRow)

    @Query("DELETE FROM studio_jobs WHERE id = :id")
    suspend fun deleteJob(id: String)
}

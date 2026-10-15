package dev.wckdboy.autobot.core.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Creative Studio records (see `:shared` `dev.wckdboy.autobot.shared.studio`). Each row keeps the
 * shared record as JSON ([json], `StudioCodec`) next to the columns the queries filter and sort
 * on, so fields added to the shared model later need no migration. Asset bytes are sealed files
 * (`StudioRepository`), never in the database.
 */

/** A Studio project ([json] is a `StudioProject`). */
@Entity(tableName = "studio_projects", indices = [Index("updatedAt")])
data class StudioProjectRow(
    @PrimaryKey val id: String,
    val name: String,
    val updatedAt: Long,
    val json: String,
)

/** One generated asset ([json] is a `StudioAsset` with its recipe). [type] is an `AssetType` name. */
@Entity(tableName = "studio_assets", indices = [Index("projectId"), Index("createdAt"), Index("favorite")])
data class StudioAssetRow(
    @PrimaryKey val id: String,
    val projectId: String,
    val type: String,
    val createdAt: Long,
    val favorite: Boolean,
    val durationMs: Long,
    val json: String,
)

/** A saved ComfyUI workflow ([json] is a `StudioWorkflow`: graph and parameter schema). */
@Entity(tableName = "studio_workflows")
data class StudioWorkflowRow(
    @PrimaryKey val id: String,
    val name: String,
    val updatedAt: Long,
    val json: String,
)

/** A prompt-library entry ([json] is a `PromptSnippet`). */
@Entity(tableName = "studio_snippets")
data class StudioSnippetRow(
    @PrimaryKey val id: String,
    val json: String,
)

/** A queued or finished job, kept so the queue survives the app being closed ([json] is a `StudioJob`). */
@Entity(tableName = "studio_jobs", indices = [Index("createdAt")])
data class StudioJobRow(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val json: String,
)

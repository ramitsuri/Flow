package io.github.aedev.flow.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "external_app_video",
)
data class ExternalAppVideoEntity(
    @PrimaryKey val id: String,
    val title: String,
)

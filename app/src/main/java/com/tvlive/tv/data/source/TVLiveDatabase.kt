package com.tvlive.tv.data.source

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.tvlive.tv.data.model.ChannelEntity
import com.tvlive.tv.data.model.PlaylistEntity
import com.tvlive.tv.data.model.RecentPlayEntity

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        RecentPlayEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TVLiveDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun recentPlayDao(): RecentPlayDao

    companion object {
        @Volatile
        private var INSTANCE: TVLiveDatabase? = null

        fun get(context: Context): TVLiveDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                TVLiveDatabase::class.java,
                "itellytv.db"
            )
                // 破坏性迁移：schema 变更时直接重建表。订阅是可重拉的，
                // 本地没有不可再生的数据，所以这个取舍成立。
                // Room 2.6+ 要求显式传 dropAllTables（旧无参重载已废弃）。
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { INSTANCE = it }
        }
    }
}

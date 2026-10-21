package net.darkrelay.deaddropfinance.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Reads and writes for the bank sync tables. These are this phone's own and are not change-logged. */
@Dao
interface SyncDao {
    @Query("SELECT * FROM connection ORDER BY created_at")
    fun connections(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connection ORDER BY created_at")
    suspend fun connectionsNow(): List<ConnectionEntity>

    @Query("SELECT * FROM connection WHERE id = :id")
    suspend fun connection(id: String): ConnectionEntity?

    @Upsert suspend fun upsertConnection(e: ConnectionEntity)

    @Query("DELETE FROM connection WHERE id = :id")
    suspend fun deleteConnection(id: String)

    @Query("SELECT * FROM linked_account ORDER BY connection_id, provider_name")
    fun linkedAccounts(): Flow<List<LinkedAccountEntity>>

    @Query("SELECT * FROM linked_account WHERE connection_id = :connectionId ORDER BY provider_name")
    suspend fun linkedOf(connectionId: String): List<LinkedAccountEntity>

    @Query("SELECT * FROM linked_account")
    suspend fun linkedNow(): List<LinkedAccountEntity>

    @Upsert suspend fun upsertLinked(e: LinkedAccountEntity)

    @Query("DELETE FROM linked_account WHERE connection_id = :connectionId")
    suspend fun deleteLinkedOf(connectionId: String)

    @Query("SELECT * FROM sync_log ORDER BY at DESC LIMIT :limit")
    fun recentLog(limit: Int): Flow<List<SyncLogEntity>>

    @Query("SELECT * FROM sync_log WHERE connection_id = :connectionId ORDER BY at DESC LIMIT 1")
    suspend fun lastLog(connectionId: String): SyncLogEntity?

    @Upsert suspend fun insertLog(e: SyncLogEntity)

    @Query("DELETE FROM sync_log WHERE connection_id = :connectionId")
    suspend fun deleteLogOf(connectionId: String)

    @Query("DELETE FROM sync_log WHERE at < :before")
    suspend fun pruneLog(before: Long)

    /** Transactions of one account including pending ones, for matching against what the provider sends. */
    @Query("SELECT * FROM txn WHERE account_id = :accountId AND deleted = 0")
    suspend fun transactionsOf(accountId: String): List<TransactionEntity>

    @Query("SELECT * FROM txn WHERE external_id LIKE :prefix || '%' AND deleted = 0")
    suspend fun transactionsWithPrefix(prefix: String): List<TransactionEntity>
}

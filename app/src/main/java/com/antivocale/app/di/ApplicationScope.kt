package com.antivocale.app.di

import javax.inject.Qualifier

/**
 * Qualifies the single process-lifetime CoroutineScope provided by [AppModule]
 * and shared by its owners: BridgeApplication (startup work), the default
 * [com.antivocale.app.data.ExternalModelRecordsProvider] (records collector),
 * HuggingFaceAuthManager (fire-and-forget user-info fetches) and LlmManager
 * (keep-alive timer, callback dispatches). TASK-438 consolidated the four
 * privately-constructed scopes, which had drifted (the records provider was
 * missing the CrashReporter handler).
 *
 * Contract:
 * - The scope carries NO dispatcher. Every launch site must pass an explicit
 *   one, so each owner keeps the execution semantics of the private scope it
 *   replaced (kotlinx would otherwise fall back to Dispatchers.Default, which
 *   is not what all owners ran on).
 * - The scope is NEVER cancelled: it lives for the whole process, like the
 *   scopes it replaced. Cancelling it would silently swallow every subsequent
 *   launch of every owner, so no owner may call cancel() on it. LlmManager's
 *   shutdown() must keep cancelling only its own keep-alive Job.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope

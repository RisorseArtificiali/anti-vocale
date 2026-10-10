package com.antivocale.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TASK-798 (GH #128): the pure post-write verification decision. The
 * reporter's provider accepted createFile and the write but never
 * materialized the file, for months, with "saved" notifications; the
 * decision converts that class into a visible failure without
 * false-failing spec-compliant providers (COLUMN_SIZE null is contract-
 * legal) or empty exports.
 */
class TranscriptFileSaverVerifyDecisionTest {

    private fun decide(report: TranscriptFileSaver.SizeReport, text: Int) =
        TranscriptFileSaver.VerifyDecision.decide(report, text)

    @Test
    fun `non-empty write with real bytes passes`() {
        assertEquals(
            TranscriptFileSaver.VerifyDecision.Outcome.PASS,
            decide(TranscriptFileSaver.SizeReport.Bytes(1204), 800))
    }

    @Test
    fun `non-empty write reported as zero bytes is the data-loss failure`() {
        assertEquals(
            TranscriptFileSaver.VerifyDecision.Outcome.FAIL,
            decide(TranscriptFileSaver.SizeReport.Bytes(0), 800))
    }

    @Test
    fun `vanished document with non-empty content fails`() {
        assertEquals(
            TranscriptFileSaver.VerifyDecision.Outcome.FAIL,
            decide(TranscriptFileSaver.SizeReport.Missing, 800))
    }

    @Test
    fun `unknown size cannot verify (contract-legal null COLUMN_SIZE)`() {
        assertEquals(
            TranscriptFileSaver.VerifyDecision.Outcome.CANNOT_VERIFY,
            decide(TranscriptFileSaver.SizeReport.Unknown, 800))
    }

    @Test
    fun `negative provider size cannot verify (off-contract -1 as unknown)`() {
        assertEquals(
            TranscriptFileSaver.VerifyDecision.Outcome.CANNOT_VERIFY,
            decide(TranscriptFileSaver.SizeReport.Bytes(-1), 800))
    }

    @Test
    fun `empty export with zero bytes passes (nothing was written)`() {
        assertEquals(
            TranscriptFileSaver.VerifyDecision.Outcome.PASS,
            decide(TranscriptFileSaver.SizeReport.Bytes(0), 0))
    }

    @Test
    fun `empty export with a vanished document passes`() {
        assertEquals(
            TranscriptFileSaver.VerifyDecision.Outcome.PASS,
            decide(TranscriptFileSaver.SizeReport.Missing, 0))
    }
}

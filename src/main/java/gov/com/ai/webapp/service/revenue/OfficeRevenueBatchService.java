package gov.com.ai.webapp.service.revenue;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import gov.com.ai.webapp.config.RevenueProperties;
import gov.com.ai.webapp.exception.BatchAlreadyRunningException;
import gov.com.ai.webapp.exception.RevenueBatchException;
import gov.com.ai.webapp.exception.RevenueRequestException;
import gov.com.ai.webapp.model.revenue.RevenueBatchResponse;
import gov.com.ai.webapp.repository.revenue.OfficeRevenueBatchRepository;
import gov.com.ai.webapp.repository.revenue.RevenueBatchLockRepository;
import gov.com.ai.webapp.repository.revenue.RevenueBatchRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfficeRevenueBatchService {

	private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("MMuuuu");

	private static final int MAX_ERROR_BYTES = 1900;

	private final OfficeRevenueBatchRepository repo;
	private final OfficeRevenueTransactionalWorker worker;
	private final RevenueBatchLockRepository locks;
	private final RevenueBatchRunRepository runs;
	private final OfficeRevenueDashboardService dashboard;
	private final RevenueProperties props;

	/** Source-data figures gathered before the run. */
	private record Precheck(long rows, long gstins, long unmappedRegistrations, long unmappedJurisdictions,
			long duplicateMappings, long duplicateSourceRows) {
	}

	public RevenueBatchResponse run(String raw) {

		String period = valid(raw);
		String runId = UUID.randomUUID().toString();
		Instant start = Instant.now();
		long t0 = System.nanoTime();

		boolean locked = false;
		boolean audited = false;
		boolean finished = false;

		// every log line of this run (any class, any thread-local logger) carries runId
		// + period
		MDC.put("runId", runId);
		MDC.put("retPeriod", period);

		try {

			locked = locks.acquire(period, runId);

			if (!locked) {
				throw new BatchAlreadyRunningException("Revenue batch already running for " + period);
			}

			log.info("===== Office revenue batch START period={} runId={} =====", period, runId);

			runs.start(runId, period);
			audited = true;

			Precheck pre = precheck(period);

			var r = retry(period, runId);

			runs.success(runId, pre.rows(), pre.gstins(), r.offices(), pre.unmappedRegistrations(),
					pre.unmappedJurisdictions(), pre.duplicateMappings());
			finished = true;

			// the data is committed and audited as SUCCESS: a cache problem must not turn
			// it into a failure
			evictCachesQuietly();

			long ms = (System.nanoTime() - t0) / 1_000_000;

			log.info(
					"===== Office revenue batch SUCCESS period={} runId={} offices={} merged={} growth={} stale={} "
							+ "elapsed={} ms =====",
					period, runId, r.offices(), r.merged(), r.growthUpdated(), r.staleDeleted(), ms);

			return new RevenueBatchResponse(runId, period, "SUCCESS", pre.rows(), pre.gstins(), r.offices(), r.merged(),
					r.growthUpdated(), r.staleDeleted(), pre.unmappedRegistrations(), pre.unmappedJurisdictions(),
					start, Instant.now(), ms, "Office revenue batch completed successfully");

		} catch (RuntimeException e) {

			// stack trace is logged once by the controller / exception handler
			log.error("===== Office revenue batch FAILED period={} runId={} elapsed={} ms cause={} =====", period,
					runId, (System.nanoTime() - t0) / 1_000_000, e.toString());

			if (audited && !finished) {
				try {
					runs.fail(runId, safe(e));
				} catch (Exception x) {
					log.error("Failed to update batch audit runId={}", runId, x);
				}
			}

			throw e;

		} finally {

			if (locked) {
				try {
					locks.release(period, runId);
				} catch (Exception e) {
					log.error("Failed to release batch lock runId={} period={}", runId, period, e);
				}
			}

			MDC.remove("runId");
			MDC.remove("retPeriod");
		}
	}

	private Precheck precheck(String p) {

		long t = System.nanoTime();

		long rows = repo.countSourceRows(p);
		long gstins = repo.countSourceGstins(p);
		long unmappedRegistrations = repo.countUnmappedRegistrations(p);
		long unmappedJurisdictions = repo.countUnmappedJurisdictions(p);
		long duplicateMappings = repo.countDuplicateMappings(p);
		long duplicateSourceRows = repo.countDuplicateSourceRows(p);

		log.info(
				"Pre-check period={} sourceRows={} gstins={} unmappedRegistration={} unmappedJurisdiction={} "
						+ "duplicateMappings={} duplicateSourceGstins={} ({} ms)",
				p, rows, gstins, unmappedRegistrations, unmappedJurisdictions, duplicateMappings, duplicateSourceRows,
				(System.nanoTime() - t) / 1_000_000);

		if (rows == 0 || gstins == 0) {
			throw new RevenueBatchException("No valid source data for " + p);
		}

		if (duplicateMappings > 0) {
			throw new RevenueBatchException(duplicateMappings + " GSTIN(s) map to multiple ST_JURI values");
		}

		if (unmappedRegistrations > 0 || unmappedJurisdictions > 0) {
			log.warn(
					"Source mapping gaps period={} unmappedRegistration={} unmappedJurisdiction={} - "
							+ "these GSTINs are excluded from office totals",
					p, unmappedRegistrations, unmappedJurisdictions);
		}

		if (duplicateSourceRows > 0) {
			log.warn("period={} {} GSTIN(s) have more than one GST_RET_3B_SUMMARY row - their amounts are summed "
					+ "once per row", p, duplicateSourceRows);
		}

		return new Precheck(rows, gstins, unmappedRegistrations, unmappedJurisdictions, duplicateMappings,
				duplicateSourceRows);
	}

	private OfficeRevenueTransactionalWorker.Result retry(String p, String id) {

		int max = Math.max(1, props.getMaxRetry());
		long delay = Math.max(0, props.getRetryBackoffMs());
		DataAccessException last = null;

		for (int i = 1; i <= max; i++) {

			long t = System.nanoTime();

			try {
				log.info("Processing attempt {}/{} period={}", i, max, p);

				var result = worker.process(p);

				log.info("Attempt {}/{} succeeded in {} ms", i, max, (System.nanoTime() - t) / 1_000_000);

				return result;

			} catch (DataAccessException e) {

				if (!(e instanceof TransientDataAccessException)) {
					throw e;
				}

				last = e;

				if (i == max) {
					break;
				}

				log.warn("Transient DB error runId={} period={} attempt={}/{} after {} ms: {} - retrying in {} ms", id,
						p, i, max, (System.nanoTime() - t) / 1_000_000, e.getMessage(), delay);

				sleep(delay);
				delay = Math.min(delay * 2, 10000);
			}
		}

		throw new RevenueBatchException("Batch failed after " + max + " attempts", last);
	}

	private void evictCachesQuietly() {

		try {
			dashboard.evictDashboardCaches();
			log.info("Dashboard caches evicted");

		} catch (Exception e) {
			log.warn("Batch succeeded but dashboard cache eviction failed - dashboards may show old data until "
					+ "the cache expires: {}", e.toString());
		}
	}

	private void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RevenueBatchException("Batch retry interrupted", e);
		}
	}

	private String valid(String p) {

		if (p == null || !p.trim().matches("(0[1-9]|1[0-2])\\d{4}")) {
			throw new RevenueRequestException("retPeriod must be MMYYYY");
		}

		String v = p.trim();

		try {
			YearMonth.parse(v, F);
		} catch (DateTimeException e) {
			throw new RevenueRequestException("Invalid retPeriod", e);
		}

		return v;
	}

	/**
	 * Error text for the audit table: includes the root cause and respects a byte
	 * limit, not a char limit.
	 */
	private String safe(Throwable e) {

		Throwable root = e;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}

		String s = e.getClass().getSimpleName() + ": " + e.getMessage();

		if (root != e) {
			s += " | root: " + root.getClass().getSimpleName() + ": " + root.getMessage();
		}

		s = s.replace('\n', ' ').replace('\r', ' ');

		while (s.getBytes(StandardCharsets.UTF_8).length > MAX_ERROR_BYTES) {
			s = s.substring(0, (int) (s.length() * 0.9));
		}

		return s;
	}
}
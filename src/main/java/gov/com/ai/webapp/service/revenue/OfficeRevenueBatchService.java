package gov.com.ai.webapp.service.revenue;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;

import gov.com.ai.webapp.config.RevenueProperties;
import gov.com.ai.webapp.exception.BatchAlreadyRunningException;
import gov.com.ai.webapp.exception.RevenueBatchException;
import gov.com.ai.webapp.exception.RevenueRequestException;
import gov.com.ai.webapp.model.revenue.RevenueBatchResponse;
import gov.com.ai.webapp.repository.revenue.OfficeRevenueBatchRepository;
import gov.com.ai.webapp.repository.revenue.RevenueBatchLockRepository;
import gov.com.ai.webapp.repository.revenue.RevenueBatchRunRepository;

import java.time.*;
import java.time.format.*;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfficeRevenueBatchService {
	private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("MMuuuu");
	private final OfficeRevenueBatchRepository repo;
	private final OfficeRevenueTransactionalWorker worker;
	private final RevenueBatchLockRepository locks;
	private final RevenueBatchRunRepository runs;
	private final OfficeRevenueDashboardService dashboard;
	private final RevenueProperties props;

	public RevenueBatchResponse run(String raw) {
		String p = valid(raw), id = UUID.randomUUID().toString(), key = p;
		Instant start = Instant.now();
		long ns = System.nanoTime();
		boolean locked = false, audit = false;
		try {
			locked = locks.acquire(key, id);
			if (!locked)
				throw new BatchAlreadyRunningException("Revenue batch already running for " + p);
			runs.start(id, p);
			audit = true;
			long rows = repo.countSourceRows(p), gstins = repo.countSourceGstins(p),
					ur = repo.countUnmappedRegistrations(p), uj = repo.countUnmappedJurisdictions(p),
					dup = repo.countDuplicateMappings(p);
			if (rows == 0 || gstins == 0)
				throw new RevenueBatchException("No valid source data for " + p);
			if (dup > 0)
				throw new RevenueBatchException(dup + " GSTIN(s) map to multiple ST_JURI values");
			if (ur > 0 || uj > 0)
				log.warn("Source mapping gaps period={} unmappedRegistration={} unmappedJurisdiction={}", p, ur, uj);
			var r = retry(p, id);
			runs.success(id, rows, gstins, r.offices(), ur, uj, dup);
			dashboard.evictDashboardCaches();
			long ms = (System.nanoTime() - ns) / 1_000_000;
			return new RevenueBatchResponse(id, p, "SUCCESS", rows, gstins, r.offices(), r.merged(), r.growthUpdated(),
					r.staleDeleted(), ur, uj, start, Instant.now(), ms, "Office revenue batch completed successfully");
		} catch (RuntimeException e) {
			if (audit)
				try {
					runs.fail(id, safe(e));
				} catch (Exception x) {
					log.error("Failed to update batch audit runId={}", id, x);
				}
			throw e;
		} finally {
			if (locked)
				try {
					locks.release(key, id);
				} catch (Exception e) {
					log.error("Failed to release batch lock runId={} period={}", id, p, e);
				}
		}
	}

	private OfficeRevenueTransactionalWorker.Result retry(String p, String id) {
		int max = Math.max(1, props.getMaxRetry());
		long delay = Math.max(0, props.getRetryBackoffMs());
		DataAccessException last = null;
		for (int i = 1; i <= max; i++) {
			try {
				return worker.process(p);
			} catch (DataAccessException e) {
				if (!(e instanceof TransientDataAccessException)) {
					throw e;
				}
				last = e;
				if (i == max)
					break;
				log.warn("Transient DB error runId={} period={} attempt={}/{}", id, p, i, max);
				sleep(delay);
				delay = Math.min(delay * 2, 10000);
			}
		}
		throw new RevenueBatchException("Batch failed after " + max + " attempts", last);
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
		if (p == null || !p.trim().matches("(0[1-9]|1[0-2])\\d{4}"))
			throw new RevenueRequestException("retPeriod must be MMYYYY");
		String v = p.trim();
		try {
			YearMonth.parse(v, F);
		} catch (DateTimeParseException e) {
			throw new RevenueRequestException("Invalid retPeriod", e);
		}
		return v;
	}

	private String safe(Throwable e) {
		String s = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		s = s.replace('\n', ' ').replace('\r', ' ');
		return s.substring(0, Math.min(2000, s.length()));
	}
}

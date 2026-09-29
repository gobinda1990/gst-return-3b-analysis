package gov.com.ai.webapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "gst.revenue")
public class RevenueProperties {
	private boolean schedulerEnabled = true;
	private String schedulerCron = "0 30 2 * * *";
	private int lockMinutes = 60;
	private int maxRetry = 3;
	private long retryBackoffMs = 2000;
	private int historyMonths = 12;
	private String masterOfficeNameColumn = "OFFICE_NAME";

	public boolean isSchedulerEnabled() {
		return schedulerEnabled;
	}

	public void setSchedulerEnabled(boolean v) {
		schedulerEnabled = v;
	}

	public String getSchedulerCron() {
		return schedulerCron;
	}

	public void setSchedulerCron(String v) {
		schedulerCron = v;
	}

	public int getLockMinutes() {
		return lockMinutes;
	}

	public void setLockMinutes(int v) {
		lockMinutes = v;
	}

	public int getMaxRetry() {
		return maxRetry;
	}

	public void setMaxRetry(int v) {
		maxRetry = v;
	}

	public long getRetryBackoffMs() {
		return retryBackoffMs;
	}

	public void setRetryBackoffMs(long v) {
		retryBackoffMs = v;
	}

	public int getHistoryMonths() {
		return historyMonths;
	}

	public void setHistoryMonths(int v) {
		historyMonths = v;
	}

	public String getMasterOfficeNameColumn() {
		return masterOfficeNameColumn;
	}

	public void setMasterOfficeNameColumn(String v) {
		masterOfficeNameColumn = v;
	}
}
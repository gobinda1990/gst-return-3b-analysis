package gov.com.ai.webapp.config;

import java.util.Locale;

public final class GrowthCacheKeys {

	private static final String NONE = "ALL";

	private GrowthCacheKeys() {
	}

	public static String scope(String period, String office) {
		return clean(period) + '|' + clean(office);
	}

	public static String taxpayers(String period, String office, String trend, String riskLevel, int page, int size) {
		return clean(period) + '|' + clean(office) + '|' + upper(trend) + '|' + upper(riskLevel) + '|' + page + '|'
				+ size;
	}

	private static String clean(String v) {
		if (v == null) {
			return NONE;
		}
		String t = v.trim();
		return t.isEmpty() ? NONE : t;
	}

	private static String upper(String v) {
		String t = clean(v);
		return NONE.equals(t) ? t : t.toUpperCase(Locale.ROOT);
	}
}
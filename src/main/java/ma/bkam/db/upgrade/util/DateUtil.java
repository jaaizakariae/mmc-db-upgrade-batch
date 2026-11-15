package ma.bkam.db.upgrade.util;

import java.time.Duration;

/**
 * Utility class for formatting durations (milliseconds → human-readable).
 * Example outputs: - 10ms - 2s:10ms - 30min:10s:20ms - 1h:15min:2s:100ms
 */
public final class DateUtil {

	private DateUtil() {
	}

	/**
	 * Formats a duration given in milliseconds into human-readable form.
	 *
	 * @param millis total duration in milliseconds
	 * @return formatted string like "2s:10ms" or "30min:10s:20ms"
	 */
	public static String formatDuration(long millis) {
		if (millis < 0) {
			return "-" + formatDuration(-millis);
		}

		Duration d = Duration.ofMillis(millis);

		long hours = d.toHours();
		long minutes = d.toMinutesPart();
		long seconds = d.toSecondsPart();
		long ms = d.toMillisPart();

		StringBuilder sb = new StringBuilder();

		if (hours > 0) {
			sb.append(hours).append("h");
		}
		if (minutes > 0) {
			if (sb.length() > 0)
				sb.append(":");
			sb.append(minutes).append("min");
		}
		if (seconds > 0) {
			if (sb.length() > 0)
				sb.append(":");
			sb.append(seconds).append("s");
		}
		if (ms > 0 || sb.length() == 0) { // always show ms if all others are zero
			if (sb.length() > 0)
				sb.append(":");
			sb.append(ms).append("ms");
		}

		return sb.toString();
	}
}
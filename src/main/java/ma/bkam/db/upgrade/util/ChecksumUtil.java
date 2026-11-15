package ma.bkam.db.upgrade.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

public final class ChecksumUtil {
	private ChecksumUtil() {
	}

	public static String sha256(Path file) throws Exception {
		byte[] data = Files.readAllBytes(file);
		return sha256(data);
	}

	public static String sha256(String text) throws Exception {
		return sha256(text.getBytes(StandardCharsets.UTF_8));
	}

	private static String sha256(byte[] bytes) throws Exception {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		byte[] hash = md.digest(bytes);
		StringBuilder sb = new StringBuilder(hash.length * 2);
		for (byte b : hash)
			sb.append(String.format("%02x", b));
		return sb.toString();
	}
}

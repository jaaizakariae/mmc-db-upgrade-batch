package ma.bkam.db.upgrade.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FileNaming {
	private FileNaming() {
	}

	public record Parsed(String sequence, int sequenceNumber, String baseName) {
	}

	public static Optional<Parsed> parse(String filename, String regexOrNull) {
		String use = (regexOrNull != null && !regexOrNull.isBlank()) ? regexOrNull
				: "^(?<seq>\\d{4})_(?<name>.+)\\.sql$";
		Pattern p = Pattern.compile(use);
		Matcher m = p.matcher(filename);
		if (!m.matches())
			return Optional.empty();
		String seq = m.group("seq");
		int seqNum = Integer.parseInt(seq);
		String name = m.group("name");
		return Optional.of(new Parsed(seq, seqNum, name));
	}
}

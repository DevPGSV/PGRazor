package dev.pgrazor;

final class LocalApiTrainer {
    final String name;
    final Long xp, caught, seen, stops, walkedMetres, eggs;

    private LocalApiTrainer(String name, Long xp, Long caught, Long seen, Long stops, Long walkedMetres, Long eggs) {
        this.name = name;
        this.xp = xp;
        this.caught = caught;
        this.seen = seen;
        this.stops = stops;
        this.walkedMetres = walkedMetres;
        this.eggs = eggs;
    }

    static boolean accept(LocalApiData data, String packet) {
        if (!data.enabled() || packet == null || packet.length() > 1800) return false;
        String[] fields = packet.split("\\|", -1);
        if (fields.length != 3 && fields.length != 9) return false;
        try {
            String id = utf16(fields[1], 256);
            String name = utf16(fields[2], 128);
            long observed = positive(fields[0]);
            LocalApiTrainer value = fields.length == 3
                ? new LocalApiTrainer(name, null, null, null, null, null, null)
                : new LocalApiTrainer(name, positive(fields[3]), count(fields[4]), count(fields[5]),
                    count(fields[6]), number(fields[7], 1_000_000_000_000L), count(fields[8]));
            return data.publish(data.trainer, id, observed, LocalApiData.Coverage.PARTIAL, value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static long positive(String text) {
        long value = number(text, Long.MAX_VALUE);
        if (value == 0) throw new IllegalArgumentException();
        return value;
    }

    private static long count(String text) { return number(text, Integer.MAX_VALUE); }

    private static long number(String text, long max) {
        if (!text.matches("[0-9]{1,19}")) throw new IllegalArgumentException();
        long value = Long.parseLong(text);
        if (value > max) throw new IllegalArgumentException();
        return value;
    }

    static String utf16(String hex, int max) {
        if (hex.isEmpty() || hex.length() % 4 != 0 || hex.length() > max * 4
                || !hex.matches("[0-9a-f]+")) throw new IllegalArgumentException();
        StringBuilder out = new StringBuilder(hex.length() / 4);
        for (int i = 0; i < hex.length(); i += 4) out.append((char) Integer.parseInt(hex.substring(i, i + 4), 16));
        String value = out.toString();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) i++;
            else if (Character.isSurrogate(ch) || Character.isISOControl(ch)) throw new IllegalArgumentException();
        }
        return value;
    }

    String json() {
        String escaped = name.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"name\":\"" + escaped + "\",\"xp\":" + xp
            + ",\"pokemon_caught\":" + caught + ",\"pokemon_encountered\":" + seen
            + ",\"pokestops_visited\":" + stops + ",\"distance_walked_km\":"
            + (walkedMetres == null ? "null" : java.math.BigDecimal.valueOf(walkedMetres, 3).toPlainString())
            + ",\"eggs_hatched\":" + eggs + "}";
    }
}

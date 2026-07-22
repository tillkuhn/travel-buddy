package net.timafe.travel.gateway;

/**
 * Masks bearer/access tokens for safe logging, keeping only a short prefix/suffix visible
 * (e.g. {@code ea***********12}) so it's obvious in logs that this is not the complete token.
 */
final class TokenMasker {

    private static final int PREFIX_LEN = 2;
    private static final int SUFFIX_LEN = 2;

    private TokenMasker() {
    }

    static String mask(String token) {
        if (token == null) {
            return "null";
        }
        if (token.length() <= PREFIX_LEN + SUFFIX_LEN) {
            return "*".repeat(token.length());
        }
        String prefix = token.substring(0, PREFIX_LEN);
        String suffix = token.substring(token.length() - SUFFIX_LEN);
        String stars = "*".repeat(token.length() - PREFIX_LEN - SUFFIX_LEN);
        return prefix + stars + suffix;
    }
}

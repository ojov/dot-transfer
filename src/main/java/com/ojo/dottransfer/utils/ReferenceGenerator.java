package com.ojo.dottransfer.utils;

import lombok.experimental.UtilityClass;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Generates client-facing transaction references.
 *
 * <p>Random rather than sequential on purpose: a counter would need coordination across instances,
 * and it would leak transaction volume to anyone holding two references. The database's unique
 * constraint on the column remains the actual guarantee - this only has to make collisions
 * vanishingly unlikely.
 */
@UtilityClass
public class ReferenceGenerator {

    private static final String PREFIX = "DOT";
    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int RANDOM_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** e.g. {@code DOT-20260902-K3PZ81QW6MTD} - 28 characters, well inside the column's 40. */
    public static String generate(LocalDate businessDate) {
        StringBuilder suffix = new StringBuilder(RANDOM_LENGTH);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            suffix.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return PREFIX + "-" + DATE_PART.format(businessDate) + "-" + suffix;
    }
}

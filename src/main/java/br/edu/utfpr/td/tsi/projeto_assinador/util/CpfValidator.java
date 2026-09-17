package br.edu.utfpr.td.tsi.projeto_assinador.util;

public final class CpfValidator {

    private CpfValidator() {
    }

    public static String sanitize(String cpf) {
        if (cpf == null) return null;
        return cpf.replaceAll("[^0-9]", "");
    }

    public static boolean isValid(String cpf) {
        String sanitized = sanitize(cpf);
        if (sanitized == null || sanitized.length() != 11) return false;

        // Reject known invalid sequences (all same digit)
        if (sanitized.matches("(\\d)\\1{10}")) return false;

        // Validate first check digit
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += Character.getNumericValue(sanitized.charAt(i)) * (10 - i);
        }
        int firstDigit = 11 - (sum % 11);
        if (firstDigit >= 10) firstDigit = 0;
        if (firstDigit != Character.getNumericValue(sanitized.charAt(9))) return false;

        // Validate second check digit
        sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += Character.getNumericValue(sanitized.charAt(i)) * (11 - i);
        }
        int secondDigit = 11 - (sum % 11);
        if (secondDigit >= 10) secondDigit = 0;
        return secondDigit == Character.getNumericValue(sanitized.charAt(10));
    }

    public static String mask(String cpf) {
        String sanitized = sanitize(cpf);
        if (sanitized == null || sanitized.length() != 11) return cpf;
        return "***." + sanitized.substring(3, 6) + "." + sanitized.substring(6, 9) + "-**";
    }
}

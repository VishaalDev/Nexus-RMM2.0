package com.nexusrmm.advisor.model;

/**
 * One concrete step the person can take. The advisor never performs actions
 * itself: every recommendation is a manual step, so nothing can be changed
 * on the machine without the person deciding to do it.
 *
 * @param code            stable identifier (useful for tests and de-duplication)
 * @param action          what to do, in one or two sentences
 * @param why             why this is expected to help
 * @param effort          how much work it is
 * @param expectedBenefit what should improve, stated modestly
 * @param caution         optional warning about a thing NOT to do or a side effect; may be null
 */
public record Recommendation(String code, String action, String why, Effort effort,
                             String expectedBenefit, String caution) {

    public static Recommendation of(String code, String action, String why, Effort effort, String benefit) {
        return new Recommendation(code, action, why, effort, benefit, null);
    }

    public Recommendation withCaution(String caution) {
        return new Recommendation(code, action, why, effort, expectedBenefit, caution);
    }
}

package com.quizopia.assessment.application;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Owner-results display-name enrichment port. Implementations resolve names for KNOWN ids
 * only, omit unknown ids, and return an empty map on any remote failure instead of throwing.
 * Lookup results never authorize, replace or rewrite authorized result rows. */
public interface StudentProfiles {
    Map<UUID, String> displayNames(Set<UUID> studentIds);
}

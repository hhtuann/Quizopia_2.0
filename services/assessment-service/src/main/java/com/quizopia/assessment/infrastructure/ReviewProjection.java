package com.quizopia.assessment.infrastructure;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.application.AttemptViews.*;
import java.util.*;

/** Pure protected projection. Production calls it only after owner/admission/membership checks. */
public final class ReviewProjection {
    private ReviewProjection() {}

    public static void require(String publicationState, String attemptState, boolean resultPresent) {
        if (!"CLOSED".equals(publicationState) || !"SUBMITTED".equals(attemptState) || !resultPresent)
            throw new PublicationFailure(409, "REVIEW_NOT_AVAILABLE");
    }

    public static Review project(
            String publicationState,
            String attemptState,
            Result result,
            List<Question> questions,
            List<Answer> answers,
            List<Answer> keys,
            List<String> earned,
            List<String> explanations) {
        require(publicationState, attemptState, result != null);
        int size = questions.size();
        if (size == 0
                || answers.size() != size
                || keys.size() != size
                || earned.size() != size
                || explanations.size() != size) throw new PublicationFailure(503, "GRADING_UNAVAILABLE");
        var rows = new ArrayList<QuestionReview>();
        for (int i = 0; i < size; i++)
            rows.add(new QuestionReview(
                    questions.get(i), answers.get(i), keys.get(i), earned.get(i), "1", explanations.get(i)));
        return new Review(result, rows);
    }
}

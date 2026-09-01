-- =============================================================================
-- V2 - The six system preparation types.
--
-- This migration IS the extensibility mechanism in practice: each type is one
-- row of declarative configuration. The engine reads `blueprint`; it never
-- switches on the type key. Adding "PMP Certification" later is an INSERT here,
-- not a code change.
--
-- Policy keys reference strategy implementations resolved by registry at
-- runtime. A key with no registered implementation degrades to the documented
-- default rather than failing, so a blueprint may name a policy before the
-- phase that implements it has shipped.
--   scoringPolicy    -> SIMPLE_CORRECTNESS (default) | PARTIAL_CREDIT | RUBRIC_WEIGHTED
--   difficultyPolicy -> ELO_TARGETED (default) | FIXED_LADDER
--   schedulerPolicy  -> FSRS_V1 (default)
-- =============================================================================

INSERT INTO preparation_types (key, name, description, icon, is_system, blueprint) VALUES

('ACADEMIC_EXAM',
 'Academic Exam',
 'Semester exams, school and university assessments. Emphasises syllabus coverage and timed mock performance.',
 'graduation-cap',
 true,
 '{
   "allowedQuestionTypes": ["MCQ", "MULTI_SELECT", "TRUE_FALSE", "SHORT_ANSWER", "LONG_ANSWER", "FLASHCARD"],
   "sessionModes": ["PRACTICE", "EXAM", "FLASHCARD_REVIEW", "DRILL"],
   "scoringPolicy": "PARTIAL_CREDIT",
   "difficultyPolicy": "ELO_TARGETED",
   "schedulerPolicy": "FSRS_V1",
   "difficultyLadder": ["EASY", "MEDIUM", "HARD", "EXPERT"],
   "targetSuccessRate": 0.78,
   "readinessWeights": {
     "coverage": 0.20, "accuracy": 0.25, "depth": 0.15,
     "retention": 0.15, "consistency": 0.10, "mock": 0.15
   },
   "evaluationCriteria": ["correctness"],
   "defaults": {
     "sessionLength": 20,
     "examLength": 50,
     "examDurationMinutes": 60,
     "timePerQuestionSec": 60,
     "immediateFeedback": true,
     "followUpsEnabled": false
   }
 }'::jsonb),

('INTERVIEW',
 'Interview',
 'Technical and behavioural interview preparation. Conversational sessions with follow-up questions and rubric-based evaluation.',
 'message-circle',
 true,
 '{
   "allowedQuestionTypes": ["SHORT_ANSWER", "LONG_ANSWER", "SCENARIO", "BEHAVIORAL", "CODING", "FLASHCARD"],
   "sessionModes": ["PRACTICE", "INTERVIEW", "FLASHCARD_REVIEW"],
   "scoringPolicy": "RUBRIC_WEIGHTED",
   "difficultyPolicy": "ELO_TARGETED",
   "schedulerPolicy": "FSRS_V1",
   "difficultyLadder": ["EASY", "MEDIUM", "HARD", "EXPERT"],
   "targetSuccessRate": 0.72,
   "readinessWeights": {
     "coverage": 0.15, "accuracy": 0.25, "depth": 0.30,
     "retention": 0.10, "consistency": 0.10, "mock": 0.10
   },
   "evaluationCriteria": ["correctness", "completeness", "technicalAccuracy", "clarity"],
   "defaults": {
     "sessionLength": 8,
     "timePerQuestionSec": 180,
     "immediateFeedback": true,
     "followUpsEnabled": true,
     "interviewLevels": ["BEGINNER", "INTERMEDIATE", "ADVANCED"]
   }
 }'::jsonb),

('CERTIFICATION',
 'Certification',
 'Vendor certification exams such as AWS, Azure or Oracle. Scenario-heavy, with multiple-select questions and a fixed passing score.',
 'badge-check',
 true,
 '{
   "allowedQuestionTypes": ["MCQ", "MULTI_SELECT", "SCENARIO", "TRUE_FALSE", "FLASHCARD"],
   "sessionModes": ["PRACTICE", "EXAM", "FLASHCARD_REVIEW", "DRILL"],
   "scoringPolicy": "PARTIAL_CREDIT",
   "difficultyPolicy": "ELO_TARGETED",
   "schedulerPolicy": "FSRS_V1",
   "difficultyLadder": ["EASY", "MEDIUM", "HARD", "EXPERT"],
   "targetSuccessRate": 0.80,
   "readinessWeights": {
     "coverage": 0.25, "accuracy": 0.25, "depth": 0.15,
     "retention": 0.15, "consistency": 0.05, "mock": 0.15
   },
   "evaluationCriteria": ["correctness"],
   "defaults": {
     "sessionLength": 20,
     "examLength": 65,
     "examDurationMinutes": 130,
     "timePerQuestionSec": 110,
     "immediateFeedback": true,
     "followUpsEnabled": false,
     "passingScore": 72
   }
 }'::jsonb),

('CODING_TEST',
 'Coding Test',
 'Timed coding assessments and technical screens. Coding problems, debugging and output prediction.',
 'code',
 true,
 '{
   "allowedQuestionTypes": ["CODING", "DEBUGGING", "OUTPUT_PREDICTION", "MCQ", "SHORT_ANSWER"],
   "sessionModes": ["PRACTICE", "EXAM", "DRILL"],
   "scoringPolicy": "PARTIAL_CREDIT",
   "difficultyPolicy": "ELO_TARGETED",
   "schedulerPolicy": "FSRS_V1",
   "difficultyLadder": ["EASY", "MEDIUM", "HARD", "EXPERT"],
   "targetSuccessRate": 0.70,
   "readinessWeights": {
     "coverage": 0.15, "accuracy": 0.20, "depth": 0.35,
     "retention": 0.10, "consistency": 0.10, "mock": 0.10
   },
   "evaluationCriteria": ["correctness", "efficiency", "readability"],
   "defaults": {
     "sessionLength": 4,
     "examLength": 4,
     "examDurationMinutes": 90,
     "timePerQuestionSec": 1200,
     "immediateFeedback": true,
     "followUpsEnabled": false
   }
 }'::jsonb),

('GENERAL_TEST',
 'General Test',
 'Any general knowledge-based assessment. A balanced default when no specialised type fits.',
 'clipboard-list',
 true,
 '{
   "allowedQuestionTypes": ["MCQ", "MULTI_SELECT", "TRUE_FALSE", "SHORT_ANSWER", "FLASHCARD"],
   "sessionModes": ["PRACTICE", "EXAM", "FLASHCARD_REVIEW", "DRILL"],
   "scoringPolicy": "SIMPLE_CORRECTNESS",
   "difficultyPolicy": "ELO_TARGETED",
   "schedulerPolicy": "FSRS_V1",
   "difficultyLadder": ["EASY", "MEDIUM", "HARD", "EXPERT"],
   "targetSuccessRate": 0.78,
   "readinessWeights": {
     "coverage": 0.20, "accuracy": 0.30, "depth": 0.15,
     "retention": 0.15, "consistency": 0.10, "mock": 0.10
   },
   "evaluationCriteria": ["correctness"],
   "defaults": {
     "sessionLength": 15,
     "examLength": 30,
     "examDurationMinutes": 40,
     "timePerQuestionSec": 60,
     "immediateFeedback": true,
     "followUpsEnabled": false
   }
 }'::jsonb),

('CUSTOM',
 'Custom',
 'Everything is permitted and every policy falls back to a safe default. Start here when nothing else fits, then narrow it in the space settings.',
 'sliders',
 true,
 '{
   "allowedQuestionTypes": ["MCQ", "MULTI_SELECT", "TRUE_FALSE", "SHORT_ANSWER", "LONG_ANSWER", "FLASHCARD", "CODING", "DEBUGGING", "OUTPUT_PREDICTION", "SCENARIO", "BEHAVIORAL"],
   "sessionModes": ["PRACTICE", "EXAM", "INTERVIEW", "FLASHCARD_REVIEW", "DRILL"],
   "scoringPolicy": "SIMPLE_CORRECTNESS",
   "difficultyPolicy": "ELO_TARGETED",
   "schedulerPolicy": "FSRS_V1",
   "difficultyLadder": ["EASY", "MEDIUM", "HARD", "EXPERT"],
   "targetSuccessRate": 0.78,
   "readinessWeights": {
     "coverage": 0.20, "accuracy": 0.25, "depth": 0.20,
     "retention": 0.15, "consistency": 0.10, "mock": 0.10
   },
   "evaluationCriteria": ["correctness"],
   "defaults": {
     "sessionLength": 15,
     "examLength": 30,
     "examDurationMinutes": 45,
     "timePerQuestionSec": 90,
     "immediateFeedback": true,
     "followUpsEnabled": false
   }
 }'::jsonb);

package pl.wordcraft.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AbcQuestionLayout {
    private AbcQuestionLayout() {
    }

    public static AbcQuestionLayout.Layout fit(
        int width, int height, String prompt, List<String> answers, boolean hasMedia, boolean feedback, AbcQuestionLayout.TextMetrics metrics, int lineHeight
    ) {
        Objects.requireNonNull(prompt);
        Objects.requireNonNull(metrics);
        if (answers.size() == 3 && lineHeight >= 1) {
            Map<AbcQuestionLayout.MeasureKey, Integer> measurements = new HashMap<>();
            AbcQuestionLayout.TextMetrics measured = (text, textWidth) -> measurements.computeIfAbsent(
                new AbcQuestionLayout.MeasureKey(text, textWidth), key -> metrics.lineCount(key.text(), key.width())
            );
            int outerWidth = Math.max(1, width - 28);
            int bodyBottom = height - 80;
            AbcQuestionLayout.Box feedbackBox = new AbcQuestionLayout.Box(14, height - 72, outerWidth, feedback ? 18 : 0);
            AbcQuestionLayout.Candidate best = null;

            for (int promptStep = 20; promptStep >= 14; promptStep--) {
                float promptScale = promptStep / 20.0F;
                int promptLines = lines(measured, prompt, outerWidth - 16, promptScale);
                AbcQuestionLayout.Box promptBox = new AbcQuestionLayout.Box(14, 45, outerWidth, pixels(promptLines, lineHeight, promptScale) + 16);
                int bodyTop = promptBox.bottom() + 6;
                int bodyHeight = bodyBottom - bodyTop;
                if (bodyHeight >= 1) {
                    for (int answerStep = 20; answerStep >= 12; answerStep--) {
                        float answerScale = answerStep / 20.0F;

                        for (int padding : new int[]{6, 4}) {
                            for (int percent = 40; percent <= 70; percent += 5) {
                                int answerWidth = hasMedia ? Math.min(outerWidth * percent / 100, outerWidth - 96) : outerWidth;
                                if (answerWidth > 30) {
                                    int[] lineCounts = new int[3];
                                    int[] heights = new int[3];
                                    int gap = padding == 6 ? 4 : 2;
                                    int required = gap * 2;

                                    for (int i = 0; i < 3; i++) {
                                        lineCounts[i] = lines(measured, answers.get(i), answerWidth - 30, answerScale);
                                        heights[i] = pixels(lineCounts[i], lineHeight, answerScale) + padding * 2;
                                        required += heights[i];
                                    }

                                    if (required <= bodyHeight) {
                                        int answerX = 14 + outerWidth - answerWidth;
                                        int extra = bodyHeight - required;
                                        int y = bodyTop;
                                        List<AbcQuestionLayout.Answer> fitted = new ArrayList<>(3);

                                        for (int i = 0; i < 3; i++) {
                                            int cardHeight = heights[i] + extra / 3 + (i < extra % 3 ? 1 : 0);
                                            fitted.add(
                                                new AbcQuestionLayout.Answer(
                                                    new AbcQuestionLayout.Box(answerX, y, answerWidth, cardHeight), answerScale, lineCounts[i], padding
                                                )
                                            );
                                            y += cardHeight + gap;
                                        }

                                        AbcQuestionLayout.Box media = new AbcQuestionLayout.Box(
                                            14, bodyTop, hasMedia ? Math.max(0, answerX - 22) : 0, bodyHeight
                                        );
                                        AbcQuestionLayout.Candidate candidate = new AbcQuestionLayout.Candidate(
                                            new AbcQuestionLayout.Layout(promptBox, promptScale, media, fitted, feedbackBox, true), answerScale, padding
                                        );
                                        if (promptStep == 20 && answerStep == 20) {
                                            return candidate.layout;
                                        }

                                        if (best == null || candidate.betterThan(best)) {
                                            best = candidate;
                                        }

                                        if (!hasMedia) {
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (best != null) {
                return best.layout;
            }

            float scale = 0.6F;
            int promptLines = lines(measured, prompt, outerWidth - 16, 0.7F);
            int promptHeight = Math.min(Math.max(0, bodyBottom - 45 - 36), pixels(promptLines, lineHeight, 0.7F) + 16);
            AbcQuestionLayout.Box promptBox = new AbcQuestionLayout.Box(14, 45, outerWidth, promptHeight);
            int bodyTop = promptBox.bottom() + 6;
            int bodyHeight = Math.max(0, bodyBottom - bodyTop);
            int answerWidth = hasMedia ? Math.max(31, outerWidth - 96) : outerWidth;
            int answerX = 14 + outerWidth - answerWidth;
            List<AbcQuestionLayout.Answer> fitted = new ArrayList<>(3);

            for (int i = 0; i < 3; i++) {
                int y = bodyTop + bodyHeight * i / 3;
                int end = bodyTop + bodyHeight * (i + 1) / 3;
                fitted.add(
                    new AbcQuestionLayout.Answer(
                        new AbcQuestionLayout.Box(answerX, y, answerWidth, Math.max(0, end - y - 2)),
                        scale,
                        lines(measured, answers.get(i), answerWidth - 30, scale),
                        4
                    )
                );
            }

            return new AbcQuestionLayout.Layout(
                promptBox, 0.7F, new AbcQuestionLayout.Box(14, bodyTop, hasMedia ? Math.max(0, answerX - 22) : 0, bodyHeight), fitted, feedbackBox, false
            );
        } else {
            throw new IllegalArgumentException("Three answers and a positive line height are required.");
        }
    }

    private static int lines(AbcQuestionLayout.TextMetrics metrics, String text, int physicalWidth, float scale) {
        return Math.max(1, metrics.lineCount(text, Math.max(1, (int)Math.floor(physicalWidth / scale))));
    }

    private static int pixels(int lines, int lineHeight, float scale) {
        return (int)Math.ceil((double)(lines * lineHeight) * scale);
    }

    public record Answer(AbcQuestionLayout.Box box, float scale, int lines, int padding) {
    }

    public record Box(int x, int y, int width, int height) {
        public int right() {
            return this.x + this.width;
        }

        public int bottom() {
            return this.y + this.height;
        }
    }

    private record Candidate(AbcQuestionLayout.Layout layout, float answerScale, int padding) {
        boolean betterThan(AbcQuestionLayout.Candidate other) {
            float readability = Math.min(this.layout.promptScale, this.answerScale);
            float otherReadability = Math.min(other.layout.promptScale, other.answerScale);
            int comparison = Float.compare(readability, otherReadability);
            if (comparison != 0) {
                return comparison > 0;
            } else {
                comparison = Float.compare(this.answerScale, other.answerScale);
                if (comparison != 0) {
                    return comparison > 0;
                } else {
                    comparison = Float.compare(this.layout.promptScale, other.layout.promptScale);
                    if (comparison != 0) {
                        return comparison > 0;
                    } else {
                        return this.padding != other.padding ? this.padding > other.padding : this.layout.media.width > other.layout.media.width;
                    }
                }
            }
        }
    }

    public record Layout(
        AbcQuestionLayout.Box prompt,
        float promptScale,
        AbcQuestionLayout.Box media,
        List<AbcQuestionLayout.Answer> answers,
        AbcQuestionLayout.Box feedback,
        boolean fullTextFits
    ) {
        public Layout {
            answers = List.copyOf(answers);
        }
    }

    private record MeasureKey(String text, int width) {
    }

    @FunctionalInterface
    public interface TextMetrics {
        int lineCount(String var1, int var2);
    }
}

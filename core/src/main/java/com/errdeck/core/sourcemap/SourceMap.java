package com.errdeck.core.sourcemap;

import com.errdeck.core.json.Json;
import com.errdeck.core.json.JsonException;
import com.errdeck.core.json.JsonFields;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 해석한 소스맵(7.3). 구간마다 객체를 만들지 않고 {@code int[]} 다섯 개(압축 열, 원본 파일 번호, 원본 줄, 원본 열, 이름 번호)에
 * 나란히 담고, 압축 줄마다 시작 위치만 따로 둔다. 구간 수십만 개를 객체로 두면 객체 헤더가 힙을 차지한다(adr/0007).
 *
 * <p>원본 코드(sourcesContent)는 담지 않는다. 크기가 크므로 필요한 줄만 {@link SourceContext} 로 그때 잘라 쓴다.
 * 만든 뒤에는 바뀌지 않으므로 여러 스레드가 함께 읽는다.
 */
public final class SourceMap {

    private static final int NONE = -1;

    private final String[] sources;
    private final String[] names;
    /** 압축 줄 i 의 구간은 [lineStarts[i], lineStarts[i + 1]) 이다. */
    private final int[] lineStarts;
    private final int[] generatedColumns;
    private final int[] sourceIndexes;
    private final int[] originalLines;
    private final int[] originalColumns;
    private final int[] nameIndexes;

    private SourceMap(String[] sources, String[] names, int[] lineStarts, int[] generatedColumns, int[] sourceIndexes,
                      int[] originalLines, int[] originalColumns, int[] nameIndexes) {
        this.sources = sources;
        this.names = names;
        this.lineStarts = lineStarts;
        this.generatedColumns = generatedColumns;
        this.sourceIndexes = sourceIndexes;
        this.originalLines = originalLines;
        this.originalColumns = originalColumns;
        this.nameIndexes = nameIndexes;
    }

    /**
     * 소스맵 v3 JSON 을 읽는다. 색인 소스맵(sections)은 지원하지 않는다(adr/0007).
     *
     * @throws IllegalArgumentException 형식이 틀렸을 때
     */
    public static SourceMap parse(byte[] json) {
        Map<String, Object> object;
        try {
            object = Json.parseObject(json);
        } catch (JsonException e) {
            throw new IllegalArgumentException("소스맵이 JSON 객체가 아니다: " + e.getMessage(), e);
        }
        try {
            JsonFields fields = JsonFields.of(object, "sourcemap");
            if (object.containsKey("sections")) {
                throw new IllegalArgumentException("색인 소스맵(sections)은 지원하지 않는다");
            }
            Long version = fields.optLong("version");
            if (version == null || version != 3) {
                throw new IllegalArgumentException("소스맵 버전 3만 읽는다: " + version);
            }
            String root = fields.optString("sourceRoot");
            List<String> rawSources = fields.list("sources", e -> e.value() == null ? "" : e.asString());
            String[] sources = rawSources.stream().map(s -> join(root, s)).toArray(String[]::new);
            String[] names = fields.list("names", JsonFields.Element::asString).toArray(String[]::new);
            return parseMappings(fields.requireString("mappings"), sources, names);
        } catch (JsonException e) {
            throw new IllegalArgumentException("소스맵 형식이 틀렸다: " + e.getMessage(), e);
        }
    }

    private static String join(String root, String source) {
        if (root == null || root.isEmpty()) {
            return source;
        }
        return root.endsWith("/") ? root + source : root + "/" + source;
    }

    private static SourceMap parseMappings(String mappings, String[] sources, String[] names) {
        // 구간 수는 쉼표와 세미콜론 수로 미리 어림해 배열을 한 번에 잡는다.
        int capacity = 1;
        int lines = 1;
        for (int i = 0; i < mappings.length(); i++) {
            char c = mappings.charAt(i);
            if (c == ',') {
                capacity++;
            } else if (c == ';') {
                capacity++;
                lines++;
            }
        }
        int[] generatedColumns = new int[capacity];
        int[] sourceIndexes = new int[capacity];
        int[] originalLines = new int[capacity];
        int[] originalColumns = new int[capacity];
        int[] nameIndexes = new int[capacity];
        int[] lineStarts = new int[lines + 1];

        int count = 0;
        int line = 0;
        int generatedColumn = 0;
        int source = 0;
        int originalLine = 0;
        int originalColumn = 0;
        int name = 0;
        boolean lineSorted = true;
        int[] position = {0};
        while (position[0] < mappings.length()) {
            char c = mappings.charAt(position[0]);
            if (c == ';') {
                position[0]++;
                if (!lineSorted) {
                    sortLine(lineStarts[line], count, generatedColumns, sourceIndexes, originalLines, originalColumns, nameIndexes);
                }
                line++;
                lineStarts[line] = count;
                generatedColumn = 0;
                lineSorted = true;
                continue;
            }
            if (c == ',') {
                position[0]++;
                continue;
            }
            // 압축 열은 같은 줄 안의 바로 앞 구간과의 차이, 나머지는 mappings 전체에서 바로 앞 값과의 차이다.
            int previousColumn = count > lineStarts[line] ? generatedColumns[count - 1] : -1;
            generatedColumn += Vlq.decode(mappings, position);
            if (generatedColumn < previousColumn) {
                lineSorted = false;
            }
            generatedColumns[count] = generatedColumn;
            if (segmentContinues(mappings, position[0])) {
                source += Vlq.decode(mappings, position);
                originalLine += Vlq.decode(mappings, position);
                originalColumn += Vlq.decode(mappings, position);
                if (source < 0 || source >= sources.length || originalLine < 0 || originalColumn < 0) {
                    throw new IllegalArgumentException("mappings 의 원본 위치가 범위를 벗어난다");
                }
                sourceIndexes[count] = source;
                originalLines[count] = originalLine;
                originalColumns[count] = originalColumn;
                if (segmentContinues(mappings, position[0])) {
                    name += Vlq.decode(mappings, position);
                    if (name < 0 || name >= names.length) {
                        throw new IllegalArgumentException("mappings 의 이름 번호가 범위를 벗어난다");
                    }
                    nameIndexes[count] = name;
                } else {
                    nameIndexes[count] = NONE;
                }
            } else {
                // 필드 하나짜리 구간은 원본이 없는 압축 코드다.
                sourceIndexes[count] = NONE;
                originalLines[count] = NONE;
                originalColumns[count] = NONE;
                nameIndexes[count] = NONE;
            }
            if (segmentContinues(mappings, position[0])) {
                throw new IllegalArgumentException("mappings 의 구간에 필드가 너무 많다");
            }
            count++;
        }
        if (!lineSorted) {
            sortLine(lineStarts[line], count, generatedColumns, sourceIndexes, originalLines, originalColumns, nameIndexes);
        }
        for (int i = line + 1; i < lineStarts.length; i++) {
            lineStarts[i] = count;
        }
        return new SourceMap(sources, names, lineStarts,
                Arrays.copyOf(generatedColumns, count), Arrays.copyOf(sourceIndexes, count), Arrays.copyOf(originalLines, count),
                Arrays.copyOf(originalColumns, count), Arrays.copyOf(nameIndexes, count));
    }

    private static boolean segmentContinues(String mappings, int position) {
        return position < mappings.length() && mappings.charAt(position) != ',' && mappings.charAt(position) != ';';
    }

    /** 생성기가 열 순서를 지키지 않은 드문 경우에만 줄 하나를 정렬한다. 같은 열이면 원래 순서를 지킨다. */
    private static void sortLine(int from, int to, int[]... columns) {
        Integer[] order = new Integer[to - from];
        for (int i = 0; i < order.length; i++) {
            order[i] = from + i;
        }
        int[] key = columns[0];
        Arrays.sort(order, (a, b) -> Integer.compare(key[a], key[b]));
        for (int[] column : columns) {
            int[] copy = new int[order.length];
            for (int i = 0; i < order.length; i++) {
                copy[i] = column[order[i]];
            }
            System.arraycopy(copy, 0, column, from, copy.length);
        }
    }

    /**
     * 압축 위치의 원본 위치. 그 줄에서 주어진 열보다 작거나 같은 마지막 구간을 이진 탐색으로 찾는다.
     *
     * @param line   1부터 센 압축 줄
     * @param column 0부터 센 압축 열. 브라우저의 스택은 열을 1부터 세므로 1을 빼서 넘긴다
     * @return 원본이 없는 구간이거나 그 앞이면 비어 있다
     */
    public Optional<OriginalPosition> lookup(int line, int column) {
        int index = line - 1;
        if (index < 0 || index >= lineStarts.length - 1 || column < 0) {
            return Optional.empty();
        }
        int low = lineStarts[index];
        int high = lineStarts[index + 1] - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (generatedColumns[mid] <= column) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        if (found < 0 || sourceIndexes[found] == NONE) {
            return Optional.empty();
        }
        int nameIndex = nameIndexes[found];
        return Optional.of(new OriginalPosition(sources[sourceIndexes[found]], sourceIndexes[found], originalLines[found] + 1,
                originalColumns[found], nameIndex == NONE ? null : names[nameIndex]));
    }

    public int segments() {
        return generatedColumns.length;
    }

    public List<String> sources() {
        return List.of(sources);
    }

    /**
     * 힙에서 차지하는 크기의 어림값. 캐시가 개수가 아니라 바이트 합계로 크기를 제한할 때 쓴다(7.4).
     * 배열은 원소 크기, 문자열은 글자당 2바이트와 객체 머리 40바이트로 센다.
     */
    public long estimatedBytes() {
        long bytes = 128L + 4L * (lineStarts.length + 5L * generatedColumns.length);
        for (String s : sources) {
            bytes += 40 + 2L * s.length();
        }
        for (String s : names) {
            bytes += 40 + 2L * s.length();
        }
        return bytes;
    }
}

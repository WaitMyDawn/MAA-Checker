package yagen.waitmydawn.checker.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本区间判定（静态预检的地基，必须有单测）。
 *
 * 支持形式：  [a,b)  [a,)  (,b]  [a]  *  ；版本按"非数字切分出的数字序列"比较。
 * 已知局限（产品要在此之上做保守策略）：
 * - 不同加载器对"1.21.1 是否落在 [1.21,1.21.1) 内"的语义不一致，本类按字面区间判定；
 *   因此静态预检只把"未安装的前置"和"loader 版本不足"当高置信结论，其余标需人工。
 * - ${...} 占位符无法在静态阶段求值，调用方应传 "*" 并单独上报。
 */
public final class VersionRanges {

    private static final Pattern PAIR = Pattern.compile("([\\[(])\\s*([^,\\]\\)]*)\\s*,\\s*([^\\[\\]\\)]*)\\s*[\\])]");
    private static final Pattern SINGLE = Pattern.compile("^\\[([^,\\[\\]]+)]$");

    private VersionRanges() {
    }

    public static boolean satisfies(String version, String range) {
        if (range == null || range.isBlank() || "*".equals(range.trim())) return true;
        String r = range.trim();
        Matcher m = PAIR.matcher(r);
        if (m.find()) {
            String low = m.group(2).trim();
            String high = m.group(3).trim();
            boolean incLow = "[".equals(m.group(1));
            boolean incHigh = r.endsWith("]");
            if (!low.isEmpty()) {
                int c = compare(version, low);
                if (c < 0 || (c == 0 && !incLow)) return false;
            }
            if (!high.isEmpty()) {
                int c = compare(version, high);
                if (c > 0 || (c == 0 && !incHigh)) return false;
            }
            return true;
        }
        Matcher s = SINGLE.matcher(r);
        if (s.find()) return compare(version, s.group(1).trim()) == 0;
        return true;
    }

    /** 数字序列比较：21.1.231 vs 21.1.247；2101.7.2-build.315 也能比较 */
    public static int compare(String a, String b) {
        List<Integer> x = numbers(a);
        List<Integer> y = numbers(b);
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            int xi = i < x.size() ? x.get(i) : 0;
            int yi = i < y.size() ? y.get(i) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    static List<Integer> numbers(String v) {
        List<Integer> out = new ArrayList<>();
        if (v == null) return out;
        for (String p : v.split("[^0-9]+")) {
            if (!p.isEmpty()) out.add(Integer.parseInt(p));
        }
        return out;
    }
}

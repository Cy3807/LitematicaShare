package share.mod;

/** 物品数量的单位换算: 1 桶 = 27 组 (一个木桶/潜影盒的容量)，1 组 = 该物品的堆叠上限 (一般 64，部分 16 或 1)。 */
final class Units {
    /** 如 13桶21组7个；0 显示 0个。 */
    static String fmt(long n, int stack) {
        int per = Math.max(1, stack);
        long barrel = 54L * per;
        long a = n / barrel, b = (n % barrel) / per, c = n % per;
        StringBuilder sb = new StringBuilder();
        if (a > 0) sb.append(a).append("桶");
        if (b > 0) sb.append(b).append("组");
        if (c > 0 || sb.length() == 0) sb.append(c).append("个");
        return sb.toString();
    }

    private Units() { }
}

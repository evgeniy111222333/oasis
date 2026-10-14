package ua.rp.chat.client.tab;

/** Two outward-facing arcs; computed only when the viewport or visible roster changes. */
public record TabLayout(int width, int height, int rows, int capacity, int total, int first,
                        float radius, int headSize, int nameCapacity, float nameScale) {
    public static TabLayout calculate(int width, int height, int count, int offset) {
        width = Math.max(1, width); height = Math.max(1, height); count = Math.max(0, count);
        float fontScale = Math.clamp(Math.min(width / 1920f, height / 1080f), 1f, 2f);
        // The HTML's 860px assembly stays full size at ordinary resolutions; shrink only to fit the actual viewport.
        float radius = Math.max(.1f, Math.min(265 * fontScale,
                Math.min(Math.max(.1f, height / 2f - 32) * 265 / 308, width * .20f)));
        int head = Math.max(1, Math.min(Math.max(8, Math.round(radius * 28 / 265)), Math.round(radius)));
        float rowHeight = Math.max(head, 30 * fontScale) + 5;
        int maxRows = Math.clamp((int)(2 * radius * Math.sin(Math.toRadians(26)) / rowHeight) + 1, 1, 5);
        int rows = Math.min(maxRows, Math.max(1, (count + 1) / 2));
        int capacity = rows * 2;
        int first = count <= capacity ? 0 : Math.floorMod(offset, count);
        float gap = radius * 10 / 265;
        float fixed = radius + radius * 28 / 265 + gap * 2 + head;
        int available = Math.max(0, (int)(width / 2f - fixed - 9));
        int label = Math.max(0, Math.min(available, Math.round(radius * .85f)));
        return new TabLayout(width, height, rows, capacity, count, first, radius, head, label, fontScale);
    }
    public int x() { return 0; }
    public int y() { return 0; }
    public int columns() { return total < 2 ? 1 : 2; }
    public int visible() { return Math.min(total, capacity); }
    public int pages() { return Math.max(1, (total + capacity - 1) / capacity); }
    public int page() { return first / capacity; }
    public int index(int i) { return total == 0 ? 0 : Math.floorMod(first + i, total); }
    public float centerX() { return width / 2f; }
    public float centerY() { return height / 2f; }
    public float artSize() { return radius * 860 / 265; }
    public float spokeLength() { return radius * 28 / 265; }
    public float nodeGap() { return radius * 10 / 265; }
    public boolean left(int i) { return i < (visible() + 1) / 2; }
    public int column(int i) { return left(i) ? 0 : 1; }
    private float arcDegrees(int i) {
        int leftCount = (visible() + 1) / 2;
        int count = left(i) ? leftCount : visible() - leftCount;
        int row = left(i) ? i : i - leftCount;
        return count <= 1 ? 0 : -26 + row * 52f / (count - 1);
    }
    public float anchorX(int i) {
        return centerX() + (float)Math.cos(Math.toRadians((left(i) ? 180 : 0) + arcDegrees(i))) * radius * 268 / 265;
    }
    public float anchorY(int i) {
        return centerY() + (float)Math.sin(Math.toRadians((left(i) ? 180 : 0) + arcDegrees(i))) * radius * 268 / 265;
    }
    public float tilt(int i) { return arcDegrees(i) * .42f; }
    public float delay(int i) { return .18f + Math.abs(arcDegrees(i)) / 26 * .14f; }
    public int textX(int i) {
        float head = spokeLength() + nodeGap();
        return Math.round(anchorX(i) + (left(i) ? -head - headSize - nodeGap() - nameCapacity : head + headSize + nodeGap()));
    }
    public int textY(int i) { return Math.round(anchorY(i) - 5 * nameScale); }
}

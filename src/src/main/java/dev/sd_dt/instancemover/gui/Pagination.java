package dev.sd_dt.instancemover.gui;

/**
 * 简单的分页计算（纯逻辑，可直接单测）：界面把长清单切成若干页，
 * 避免在窗口里塞满控件（也让布局在任何分辨率下都不会越界）。
 */
public final class Pagination {

    private int pageSize;
    private int page;

    public Pagination(int pageSize) {
        this.pageSize = Math.max(1, pageSize);
    }

    public int pageSize() {
        return pageSize;
    }

    /** 调整每页行数（窗口大小变化时用），并把当前页夹回合法范围。 */
    public void setPageSize(int pageSize) {
        this.pageSize = Math.max(1, pageSize);
    }

    public int page() {
        return page;
    }

    public void setPage(int page) {
        this.page = Math.max(0, page);
    }

    /** 总页数（至少 1 页，哪怕一行都没有）。 */
    public int pageCount(int totalRows) {
        if (totalRows <= 0) {
            return 1;
        }
        return (totalRows + pageSize - 1) / pageSize;
    }

    /** 把当前页夹到合法范围（行数变化后调用）。 */
    public void clamp(int totalRows) {
        page = Math.min(Math.max(0, page), pageCount(totalRows) - 1);
    }

    /** 当前页第一行的下标。 */
    public int firstIndex(int totalRows) {
        clamp(totalRows);
        return page * pageSize;
    }

    /** 当前页最后一行的下标（不含）。 */
    public int lastIndex(int totalRows) {
        return Math.min(totalRows, firstIndex(totalRows) + pageSize);
    }

    public boolean hasPrevious() {
        return page > 0;
    }

    public boolean hasNext(int totalRows) {
        return page + 1 < pageCount(totalRows);
    }

    public void next(int totalRows) {
        if (hasNext(totalRows)) {
            page++;
        }
    }

    public void previous() {
        if (hasPrevious()) {
            page--;
        }
    }
}

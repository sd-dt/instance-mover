package dev.sd_dt.instancemover.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 分页计算（纯逻辑）：总页数、当前页范围、翻页边界、页大小变化后的夹取。 */
class PaginationTest {

    @Test
    @DisplayName("总页数：0 行也算 1 页，除不尽向上取整")
    void pageCount() {
        Pagination pagination = new Pagination(8);
        assertEquals(1, pagination.pageCount(0));
        assertEquals(1, pagination.pageCount(8));
        assertEquals(2, pagination.pageCount(9));
        assertEquals(3, pagination.pageCount(20));
        assertEquals(8, pagination.pageSize());
    }

    @Test
    @DisplayName("当前页范围：first/last 正确，末页不越界")
    void pageRange() {
        Pagination pagination = new Pagination(8);
        assertEquals(0, pagination.firstIndex(20));
        assertEquals(8, pagination.lastIndex(20));

        pagination.next(20);
        assertEquals(8, pagination.firstIndex(20));
        assertEquals(16, pagination.lastIndex(20));

        pagination.next(20);
        assertEquals(16, pagination.firstIndex(20));
        assertEquals(20, pagination.lastIndex(20), "最后一页取到总行数为止");
        assertFalse(pagination.hasNext(20));

        pagination.next(20);
        assertEquals(16, pagination.firstIndex(20), "到底后再点下一页不越界");
    }

    @Test
    @DisplayName("上一页 / 第一页边界")
    void previousBoundary() {
        Pagination pagination = new Pagination(5);
        assertFalse(pagination.hasPrevious());
        pagination.previous();
        assertEquals(0, pagination.page());

        pagination.next(12);
        pagination.next(12);
        assertEquals(2, pagination.page());
        assertTrue(pagination.hasPrevious());
        pagination.previous();
        assertEquals(1, pagination.page());
    }

    @Test
    @DisplayName("页大小变化（窗口缩放）后当前页被夹回合法范围")
    void clampingAfterPageSizeChange() {
        Pagination pagination = new Pagination(4);
        pagination.setPage(3);
        assertEquals(3, pagination.page());

        pagination.setPageSize(10);
        pagination.clamp(30);
        assertEquals(2, pagination.page(), "10 行/页时最多 3 页（0..2）");
        assertEquals(20, pagination.firstIndex(30), "第 3 页（page=2）从第 20 行开始");

        pagination.setPageSize(0);
        assertEquals(1, pagination.pageSize(), "页大小至少为 1");
        pagination.setPageSize(30);
        pagination.clamp(30);
        assertEquals(0, pagination.page());
        assertEquals(0, pagination.firstIndex(30));
        assertEquals(30, pagination.lastIndex(30));
    }

    @Test
    @DisplayName("空列表也不炸：0 行、只有 1 页")
    void emptyList() {
        Pagination pagination = new Pagination(8);
        pagination.clamp(0);
        assertEquals(0, pagination.page());
        assertEquals(0, pagination.firstIndex(0));
        assertEquals(0, pagination.lastIndex(0));
        assertFalse(pagination.hasNext(0));
    }
}

package com.jiaoyu.user_api.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 精确 offset 分页器。
 *
 * <p>为什么不用 PageRequest：Spring Data 的 PageRequest 是「页号制」，最终 offset 由
 * pageNumber * pageSize 算出来。当前端用 RealWorld 规范的 {@code ?limit=10&offset=7} 时，
 * PageRequest.of(7/10=0, 10) 会退回第 0 页，返回错误的数据。</p>
 *
 * <p>SimpleJpaRepository 内部用的是 {@code pageable.getOffset()} 与 {@code pageable.getPageSize()}
 * 拼 SQL 的 limit/offset，所以只要 getOffset() 返回真实偏移，分页就是精确的。</p>
 */
public class OffsetPageable implements Pageable {

    private final long offset;
    private final int pageSize;
    private final Sort sort;

    /**
     * @param offset   起始偏移，负数归 0
     * @param pageSize 每页条数，必须 &gt; 0
     * @param sort     排序，null 视为无序
     */
    public OffsetPageable(long offset, int pageSize, Sort sort) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("每页条数必须大于 0");
        }
        this.offset = Math.max(0L, offset);
        this.pageSize = pageSize;
        this.sort = sort == null ? Sort.unsorted() : sort;
    }

    @Override
    public int getPageNumber() {
        // 页号仅用于 Page 对象内部展示，真实分页以 getOffset() 为准
        return (int) (offset / pageSize);
    }

    @Override
    public int getPageSize() {
        return pageSize;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return sort;
    }

    @Override
    public Pageable next() {
        return new OffsetPageable(offset + pageSize, pageSize, sort);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new OffsetPageable(Math.max(0L, offset - pageSize), pageSize, sort) : first();
    }

    @Override
    public Pageable first() {
        return new OffsetPageable(0L, pageSize, sort);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetPageable((long) pageNumber * pageSize, pageSize, sort);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0L;
    }
}

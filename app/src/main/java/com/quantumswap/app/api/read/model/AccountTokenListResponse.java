package com.quantumswap.app.api.read.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.google.gson.annotations.SerializedName;

/**
 * Response for GET /account/{address}/tokens/{pageIndex}. Mirrors the desktop
 * wallet's expected payload: { pageCount, items: [AccountTokenSummary...] }.
 */
public class AccountTokenListResponse {
  public static final String SERIALIZED_NAME_PAGE_COUNT = "pageCount";
  @SerializedName(SERIALIZED_NAME_PAGE_COUNT)
  private Integer pageCount;

  public static final String SERIALIZED_NAME_ITEMS = "items";
  @SerializedName(SERIALIZED_NAME_ITEMS)
  private List<AccountTokenSummary> items = new ArrayList<AccountTokenSummary>();

  public Integer getPageCount() { return pageCount; }
  public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }

  public List<AccountTokenSummary> getItems() { return items; }
  public void setItems(List<AccountTokenSummary> items) { this.items = items; }

  @Override
  public boolean equals(java.lang.Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AccountTokenListResponse other = (AccountTokenListResponse) o;
    return Objects.equals(this.pageCount, other.pageCount) &&
        Objects.equals(this.items, other.items);
  }

  @Override
  public int hashCode() {
    return Objects.hash(pageCount, items);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class AccountTokenListResponse {\n");
    sb.append("    pageCount: ").append(pageCount).append("\n");
    sb.append("    items: ").append(items).append("\n");
    sb.append("}");
    return sb.toString();
  }
}

package tacos.api.dto;

import java.util.List;

import lombok.Value;

@Value
public class OrderHistoryPageResponse {
  List<OrderSummaryResponse> content;
  int page;
  int size;
  long totalElements;
  long totalPages;
}

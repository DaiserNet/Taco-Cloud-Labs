package tacos.api.dto;

import java.util.List;

import lombok.Value;

@Value
public class TacoSearchResponse {
  List<TacoResponse> content;
  int page;
  int size;
  long totalElements;
  int totalPages;
}

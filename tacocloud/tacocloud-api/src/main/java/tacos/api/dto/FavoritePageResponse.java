package tacos.api.dto;

import java.util.List;

import lombok.Value;

@Value
public class FavoritePageResponse {
  List<FavoriteResponse> content;
  int page;
  int size;
  long totalElements;
  int totalPages;
}

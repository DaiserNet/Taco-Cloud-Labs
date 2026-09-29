package tacos.api.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.Value;

@Value
public class TacoRankingResponse {
  String tacoId;
  String tacoName;
  BigDecimal average;
  long count;
  List<Long> distribution;
}

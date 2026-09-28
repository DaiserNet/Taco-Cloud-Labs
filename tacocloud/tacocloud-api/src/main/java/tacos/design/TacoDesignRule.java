package tacos.design;

import java.util.List;

public interface TacoDesignRule {
  List<TacoDesignViolation> check(TacoDesignContext design);
}

package org.mecong;

import java.util.List;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Config {

  String baseUrl;
  String referer;

  Long tgId;
  String botToken;

  List<MultiClickerTask.RequestConfig> players;
}

package org.mecong;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;

import static java.util.Base64.getDecoder;

@Slf4j
public class MultiClickerTask {
  protected static Config config;
  protected static Communication communication;
  private static final Random random = new Random();
  private static final ObjectMapper mapper = new ObjectMapper();


  @SneakyThrows
  public static void main(String[] args) {
    config = mapper.readValue(new File(args[0]), new TypeReference<>() {});

    communication = new Communication(config.getBotToken(), config.getTgId());

    scheduleDailyJob();

    config.getPlayers().forEach(requestConfig -> {
      requestConfig.startSyncTimer();
      requestConfig.scheduleTapTimer();
    });
  }

  private static void scheduleDailyJob() throws SchedulerException {
    // Create a JobDetail instance
    JobDetail job = JobBuilder.newJob(DailyJob.class).withIdentity("dailyJob", "group1").build();

    // Create a Trigger instance with a cron expression
    Trigger trigger = TriggerBuilder.newTrigger()
                          .withIdentity("cronTrigger", "group1")
                          .withSchedule(CronScheduleBuilder.cronSchedule("0 5 19,20,21 * * ?"))
                          .build();

    // Create a Scheduler instance
    Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
    scheduler.start();
    scheduler.scheduleJob(job, trigger);
  }

  @Data
  @NoArgsConstructor
  @FieldDefaults(level = AccessLevel.PRIVATE)
  public static class RequestConfig {
    String name;
    int maxTaps;
    String token;
    double priceForGrand;
    boolean sendToTg;
    int tapProbabilityPercent = 50;
    boolean buyUpgradesAutomatically;

    public void startSyncTimer() {
      new Timer().scheduleAtFixedRate(new TimerTask() {
        @Override
        public void run() {
          try {
            sendSyncRequest();
          } catch (IOException e) {
            log.error(e.getMessage(), e);
          }
        }
      }, 0, Duration.ofHours(1).toMillis());
    }

    public void scheduleTapTimer() {
      int intervalMinutes = (int) (Math.floor(maxTaps / 3.0 / 60));
      int randomTime = intervalMinutes - random.nextInt(10);

      Duration duration = Duration.ofMinutes(randomTime);

      new Timer().schedule(new TimerTask() {
        @Override
        public void run() {
          try {
            scheduleTapTimer();

            sendTapRequest(duration.toSeconds() * 3);
            sendUpgradesToBuyRequest();

          } catch (IOException e) {
            log.error(e.getMessage(), e);
          }
        }
      }, duration.toMillis());
    }

    private void sendUpgradesToBuyRequest() throws IOException {

   /*
            "id": "adv_integration_3107",
            "name": "This is fine",
            "releaseAt": "2024-07-31T11:00:00.000Z",
            "expiresAt": "2024-08-02T11:00:00.000Z",
            "price": 1498456,
            "profitPerHour": 53665,
            "condition": null,
            "cooldownSeconds": 6860,
            "section": "Specials",
            "level": 12,
            "currentProfitPerHour": 47350,
            "profitPerHourDelta": 6315,
            "isAvailable": true,
            "isExpired": false,
            "totalCooldownSeconds": 7200

            "condition": {
                "_type": "ReferralCount",
                "referralCount": 10
            },
    */

      log.info("Requested upgrades-for-buy for: {}  ", this.name);

      JsonNode actualObj = requestObject("upgrades-for-buy");

      JsonNode upgradesForBuy = actualObj.get("upgradesForBuy");

      StringBuilder potentialUpgrades = new StringBuilder();

      for (JsonNode upgrade : upgradesForBuy) {
        double profitPerHourDelta = upgrade.get("profitPerHourDelta").asDouble();
        double price = upgrade.get("price").asDouble();
        JsonNode cooldownSeconds = upgrade.get("cooldownSeconds");
        boolean cooldown = cooldownSeconds != null && cooldownSeconds.asInt() > 0;
        boolean isAvailable = upgrade.get("isAvailable").asBoolean();
        boolean isExpired = upgrade.get("isExpired").asBoolean();

        if (canBuy(isAvailable, isExpired, profitPerHourDelta, price)) {
          potentialUpgrades.append(upgrade.get("name").asText()).append(":").append(upgrade.get("section").asText());
          if (cooldown) {
            potentialUpgrades.append(" - ").append(Duration.ofSeconds(cooldownSeconds.asInt()).toMinutes()).append("m");
          } else if (buyUpgradesAutomatically) {
            String id = upgrade.get("id").asText();
            buyUpgrade(id);
          }
          potentialUpgrades.append("\n");

        }
      }

      String potentialUpgradesString = potentialUpgrades.toString();
      if (!potentialUpgradesString.isBlank()) {
        log.info("{} upgrades > \n {}", RequestConfig.this.name, potentialUpgradesString);
        if (RequestConfig.this.sendToTg) {
          communication.sendText(RequestConfig.this.name + " upgrades >\n" + potentialUpgradesString);
        }
      }
    }

    private boolean canBuy(boolean isAvailable, boolean isExpired, double profitPerHourDelta, double price) {
      return isAvailable && !isExpired && profitPerHourDelta / price >= 1000 / this.priceForGrand;
    }


    private void buyUpgrade(String upgradeId) throws IOException {
      HttpURLConnection conn = getHttpURLConnection("buy-upgrade");

      conn.setDoOutput(true);

      byte[] input = "{\"upgradeId\":\"%s\",\"timestamp\":%d}".formatted(
          upgradeId,
          new Date().getTime()).getBytes(StandardCharsets.UTF_8);

      try (OutputStream os = conn.getOutputStream()) {
        os.write(input, 0, input.length);
      }

      int responseCode = conn.getResponseCode();
      log.info("Buy upgrade Response Code: {} for '{}' upgrade: '{}'", responseCode, this.name, upgradeId);

      conn.disconnect();
    }

    private void sendTapRequest(long tapAmount) throws IOException {
      int randomizer = random.nextInt(100) + 1;

      if (randomizer > this.getTapProbabilityPercent()) {
        log.info("Taps for {} skipped", this.name);
        return;
      }

      HttpURLConnection conn = getHttpURLConnection("tap");

      conn.setDoOutput(true);

      byte[] input = "{\"count\":%d,\"availableTaps\":%d,\"timestamp\":%d}".formatted(
          maxTaps,
          tapAmount,
          new Date().getTime()).getBytes(StandardCharsets.UTF_8);

      try (OutputStream os = conn.getOutputStream()) {
        os.write(input, 0, input.length);
      }

      int responseCode = conn.getResponseCode();
      log.info("Tap Response Code: {} for '{}' taps sent: {}", responseCode, this.name, tapAmount);

      conn.disconnect();
    }

    private void sendSyncRequest() throws IOException {
      HttpURLConnection conn = getHttpURLConnection("sync");

      conn.connect();

      int responseCode = conn.getResponseCode();
      log.info("Sync Response Code: {}  for '{}'", responseCode, this.name);

      conn.disconnect();
    }

    public void sendConfigRequest() {
      JsonNode jsonNode = requestObject("config");

      /*
      "dailyCipher": {
          "cipher": "U0N6SWVBU",
          "bonusCoins": 1000000,
          "isClaimed": true,
          "remainSeconds": 2959
        },
        "feature": [
          "airdrop_connect_ton_wallet"
        ],
        "dailyKeysMiniGame": {
          "startDate": "2024-08-03T20:00:00.000Z",
          "levelConfig": "- a - - - -.- a - b c c.- 0 0 b d -.f e e e d x.f - g h h x.- - g - - x",
          "youtubeUrl": "https://www.youtube.com/watch?v=Q7t_Cix-VC8",
          "bonusKeys": 1,
          "isClaimed": true,
          "totalSecondsToNextAttempt": 360,
          "remainSecondsToGuess": -79111.917,
          "remainSeconds": 6558.813,
          "remainSecondsToNextAttempt": -78811.917
        }
       */
      log.info("Requested config for: {}  ", this.name);

      JsonNode dailyCipherNode = jsonNode.get("dailyCipher");
      boolean isClaimedDailyCipher = dailyCipherNode.get("isClaimed").booleanValue();
      if (!isClaimedDailyCipher) {
        String cipher = dailyCipherNode.get("cipher").asText();
        String cipherWord = new String(getDecoder().decode(cipher.substring(0, 3) + cipher.substring(4)));
        communication.sendText("New daily cypher:");
        communication.sendText(cipherWord);
      }

      JsonNode dailyKeysMiniGameNode = jsonNode.get("dailyKeysMiniGame");
      boolean isClaimedDailyKeysMiniGameNode = dailyKeysMiniGameNode.get("isClaimed").booleanValue();
      if (!isClaimedDailyKeysMiniGameNode) {
        String levelConfig = dailyKeysMiniGameNode.get("levelConfig").asText();
        communication.sendText("New mini game:");
        communication.sendText(levelConfig);
      }
    }

    @SneakyThrows
    private JsonNode requestObject(String urlString) {
      HttpURLConnection conn = getHttpURLConnection(urlString);

      conn.connect();

      StringBuilder bodyOut = new StringBuilder();
      if (conn.getInputStream() != null) {
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String inputLine;

        while ((inputLine = in.readLine()) != null) {
          bodyOut.append(inputLine);
        }
        in.close();
      }

      conn.disconnect();

      JsonNode actualObj = mapper.readTree(bodyOut.toString());

      conn.disconnect();
      return actualObj;
    }


    @SneakyThrows
    HttpURLConnection getHttpURLConnection(String urlString) {
      URL url = new URI(config.getBaseUrl() + urlString).toURL();
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setRequestProperty("accept", "application/json");
      conn.setRequestProperty("accept-language", "en-US,en;q=0.9,ru-RU;q=0.8,ru;q=0.7");
      conn.setRequestProperty("authorization", "Bearer " + this.token);
      conn.setRequestProperty("content-type", "application/json");
      conn.setRequestProperty("priority", "u=1, i");
      conn.setRequestProperty("referer", config.getReferer());
      conn.setRequestProperty("sec-ch-ua-mobile", "?0");
      conn.setRequestProperty("sec-fetch-dest", "empty");
      conn.setRequestProperty("sec-fetch-mode", "cors");
      conn.setRequestProperty("sec-fetch-site", "same-site");
      return conn;
    }
  }
}

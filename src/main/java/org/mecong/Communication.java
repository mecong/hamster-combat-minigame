package org.mecong;

import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Slf4j
public class Communication extends TelegramLongPollingBot {
  private final Long myTgId;

  public Communication(String botToken, Long tgId) {
    super(botToken);
    this.myTgId = tgId;
  }

  @Override
  public String getBotUsername() {
    return "Info Bot";
  }

  @Override
  public void onUpdateReceived(Update update) {
    log.info("Received update: {}", update);
  }

  void sendText(String what) {
    SendMessage sm = SendMessage.builder()
                         .chatId(myTgId) //Who are we sending a message to
                         .text(what)
                         .build();    //Message content
    try {
      execute(sm);                        //Actually sending the message
    } catch (TelegramApiException e) {
      log.error("Cannot send message in TG: {}", e.getMessage());
    }
  }
}

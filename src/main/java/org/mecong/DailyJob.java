package org.mecong;

import org.quartz.Job;
import org.quartz.JobExecutionContext;

public class DailyJob implements Job {
  @Override
  public void execute(JobExecutionContext context) {
    MultiClickerTask.config.getPlayers().get(0).sendConfigRequest();
  }

}

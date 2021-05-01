package harness;

public class Callback {
  private static final long MS = 1000000;
  long startTime = 0;

  public void startWarmup() {
    salutation(true);
    startTime = System.nanoTime();
  }
  public void stopWarmup() {
    long duration = System.nanoTime() - startTime;
    message(true,true,duration/MS);
  }
  public void start() {
    salutation(false);
    startTime = System.nanoTime();
  }
  public void stop() {
    long duration = System.nanoTime() - startTime;
    message(true,false,duration/MS);
  }

  private void salutation(boolean warmup) {
    System.err.print("===== Philo starting ");
    System.err.println((warmup ? "warmup " : "") + "=====");
    System.err.flush();
  }

  private void message(boolean valid, boolean warmup, long elapsed) {
    System.err.print("===== Philo");
    if (valid) {
      System.err.print(warmup ? " completed warmup " : " PASSED ");
      System.err.print("in " + elapsed + " msec ");
    } else {
      System.err.print(" FAILED " + (warmup ? "warmup " : ""));
    }
    System.err.println("=====");
    System.err.flush();

  }
}

package atomicity;

class Account {
  int balance;

  public Account() {
    balance = 100;
  }

  synchronized int read(int id) {
    return balance;
  }

  void update(int a, int id2) {
    int tmp = read(id2);
    if (id2 == 1) {
      try {
        // Wait for the second thread to interleave and finish its (second) execution
        Thread.sleep(7000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    synchronized (this) {
      balance = tmp + a;
    }
    if (id2 == 2) {
      try {
        // Wait for the first to wake up and conflict before dying
        Thread.sleep(15000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }
}

package atomicity;

class Stack {
  Object[] storage = new Object[10];
  int item = -1;
  public static Stack makeStack() {
    return new Stack();
  }
  public synchronized Object pop() {
    Object res = storage[item];
    storage[item--] = null;
    return res;
  }
  public synchronized void push(Object o) {
    storage[++item] = o;
  }
  public synchronized boolean empty() {
    return (item == -1);
  }
}

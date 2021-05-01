package atomicity;

class MyObject4 {
  
  int a;
  double b;
  
  public MyObject4() {
    a = 0;
    b = 0.0;
  }
  
  public MyObject4(int k) {
    a = k;
    b = 0.0;
  }
  
  public MyObject4(double l) {
    a = 0;
    b = l;
  }
  
  public MyObject4(int k, double l) {
    a = k;
    b = l;
  }
  
  public void setInt(int d) {
    a = d;
  }
  
  public int getInt() {
    return a;
  }
  
}

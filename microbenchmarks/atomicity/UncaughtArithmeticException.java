package atomicity;

public class UncaughtArithmeticException {

    public static synchronized int divide(int a, int b) {
      return a / b;
    }
    
    public static int divideHelper(int a, int b) {
      return divide(4, 0);
    }
    
    public static void main(String[] args) {
      
      System.out.println(divideHelper(4, 0));
      
      if(args.length > 1) {
        int arg0 = Integer.parseInt(args[0]);
        int arg1 = Integer.parseInt(args[1]);
        System.out.println( divide(arg0, arg1) );
      }
      System.out.println("end");
    }

}

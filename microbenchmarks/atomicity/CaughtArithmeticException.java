package atomicity;

public class CaughtArithmeticException {

    public static int divide(int a, int b) {
      return a / b;
    }
    
    public static int divideHelper(int a, int b) {
      try {
        return divide(4, 0);
      } catch(ArithmeticException e) {
        System.out.println("comes here");
      }
      System.out.println("after catch block");
      return 0;
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

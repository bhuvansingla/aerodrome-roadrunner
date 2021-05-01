package atomicity;

class Consumer extends Thread { 
    Producer producer; 
     
    Consumer(Producer p) { 
        producer = p; 
    } 
  
    public void run() { 
        try { 
            while ( true ) { 
                String message = producer.getMessage(); 
                System.out.println("Got message: " + Integer.parseInt(message)); 
                sleep( 2000 ); 
            } 
        }  
        catch( InterruptedException e ) { } 
    } 
  
    @SuppressWarnings("deprecation")
    public static void main(String args[]) { 
        Producer producer = new Producer(); 
        producer.start(); 
        new Consumer( producer ).start();
        System.runFinalizersOnExit(true);
    } 
} 
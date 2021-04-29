  package tools.aerodrome;

  import acme.util.count.Counter;
  import rr.state.ShadowVar;

  public class ADVarClocks implements ShadowVar {
      public final ADVectorClock read;
      public final ADVectorClock write;
      public final ADVectorClock readcheck;

      private static Counter ADVarClocks = new Counter("AD", "Other (Ignore)");

      public ADVarClocks(int dim) {
          read = new ADVectorClock(dim);
          write = new ADVectorClock(dim);
          readcheck = new ADVectorClock(dim);
          ADVarClocks.inc();
      }

      @Override
      public String toString() {
          return "R" + read + " - " + "W" + write + "RCHK"+readcheck;
      }
  }

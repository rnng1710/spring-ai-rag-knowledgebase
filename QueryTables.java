import java.sql.*;
public class QueryTables {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:mysql://192.168.193.128:3309/ncp?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
    try (Connection c = DriverManager.getConnection(url, "root", "root");
         Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("show tables")) {
      while (rs.next()) {
        System.out.println(rs.getString(1));
      }
    }
  }
}

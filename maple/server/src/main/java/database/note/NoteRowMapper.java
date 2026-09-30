package database.note;

import model.Note;

import java.sql.ResultSet;
import java.sql.SQLException;

public class NoteRowMapper {
    public static Note map(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        String message = rs.getString("message");
        String from = rs.getString("from");
        String to = rs.getString("to");
        long timestamp = rs.getLong("timestamp");
        int fame = rs.getInt("fame");
        return new Note(id, message, from, to, timestamp, fame);
    }
}

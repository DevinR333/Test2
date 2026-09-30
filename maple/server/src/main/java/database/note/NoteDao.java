package database.note;

import database.DaoException;
import model.Note;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Notes (in-game memos), with plain JDBC. */
public class NoteDao {

    public void save(Note note) {
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO notes (`message`, `from`, `to`, `timestamp`, `fame`, `deleted`) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, note.message());
            ps.setString(2, note.from());
            ps.setString(3, note.to());
            ps.setLong(4, note.timestamp());
            ps.setInt(5, note.fame());
            ps.setInt(6, 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException(String.format("Failed to save note: %s", note.toString()), e);
        }
    }

    public List<Note> findAllByTo(String to) {
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM notes WHERE `deleted` = 0 AND `to` = ?")) {
            ps.setString(1, to);
            List<Note> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(NoteRowMapper.map(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new DaoException(String.format("Failed to find notes sent to: %s", to), e);
        }
    }

    public Optional<Note> delete(int id) {
        try (Connection c = DatabaseConnection.getConnection()) {
            Optional<Note> note;
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM notes WHERE `deleted` = 0 AND `id` = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    note = rs.next() ? Optional.of(NoteRowMapper.map(rs)) : Optional.empty();
                }
            }
            if (note.isEmpty()) {
                return Optional.empty();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE notes SET `deleted` = 1 WHERE `id` = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
            return note;
        } catch (SQLException e) {
            throw new DaoException(String.format("Failed to delete note with id: %d", id), e);
        }
    }
}

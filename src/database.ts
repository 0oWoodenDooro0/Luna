import Database from 'better-sqlite3';
import { Poll, type PollJSON } from './models/Poll.js';

// This will create a file named 'luna.db' in your project's root directory
const db = new Database('luna.db');

// --- Setup the database table ---
// This function runs once to ensure the 'polls' table exists
function setupDatabase() {
  db.exec(`
    CREATE TABLE IF NOT EXISTS polls (
      id TEXT PRIMARY KEY,
      topic TEXT NOT NULL,
      options TEXT NOT NULL,
      userVotes TEXT NOT NULL
    )
  `);
  console.log('Database setup complete. "polls" table is ready.');
}

// Run the setup
setupDatabase();

// --- Database Functions ---

/**
 * Saves a Poll object to the database.
 * If a poll with the same ID already exists, it will be updated.
 * @param poll The Poll instance to save.
 */
export function savePoll(poll: Poll) {
  const stmt = db.prepare(`
    INSERT INTO polls (id, topic, options, userVotes)
    VALUES (?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET
      topic = excluded.topic,
      options = excluded.options,
      userVotes = excluded.userVotes
  `);

  const pollData = poll.toJSON();
  stmt.run(
    pollData.id,
    pollData.topic,
    JSON.stringify(pollData.options), // Store arrays/objects as JSON strings
    JSON.stringify(pollData.userVotes)
  );
}

/**
 * Retrieves a Poll from the database by its ID.
 * @param id The message ID of the poll.
 * @returns A Poll instance or null if not found.
 */
export function getPoll(id: string): Poll | null {
  const stmt = db.prepare('SELECT * FROM polls WHERE id = ?');
  const row = stmt.get(id) as any;

  if (!row) {
    return null;
  }

  // Reconstruct the Poll object from the database row
  const pollData: PollJSON = {
    id: row.id,
    topic: row.topic,
    options: JSON.parse(row.options),
    userVotes: JSON.parse(row.userVotes),
  };

  return Poll.fromJSON(pollData);
}

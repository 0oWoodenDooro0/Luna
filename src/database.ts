import Database from 'better-sqlite3';
import { Bet, type BetJSON } from './models/Bet.js';

// This will create a file named 'luna.db' in your project's root directory
const db = new Database('luna.db');

// --- Setup the database table ---
// This function runs once to ensure the 'polls' table exists
function setupDatabase() {
  db.exec(`
    CREATE TABLE IF NOT EXISTS bets (
      id TEXT PRIMARY KEY,
      creatorId TEXT NOT NULL,
      channelId TEXT NOT NULL,
      topic TEXT NOT NULL,
      options TEXT NOT NULL,
      endTime INTEGER,
      isActive INTEGER NOT NULL DEFAULT 1,
      winningOption INTEGER
    )
  `);

  db.exec(`
    CREATE TABLE IF NOT EXISTS wagers (
      wagerId INTEGER PRIMARY KEY AUTOINCREMENT,
      betId TEXT NOT NULL,
      userId TEXT NOT NULL,
      optionIndex INTEGER NOT NULL,
      amount INTEGER NOT NULL,
      FOREIGN KEY (betId) REFERENCES bets(id),
      FOREIGN KEY (userId) REFERENCES users(userId)
    )
  `);
  db.exec(`
    CREATE TABLE IF NOT EXISTS users (
      userId TEXT PRIMARY KEY,
      points INTEGER NOT NULL DEFAULT 0
    )
  `);
  db.exec(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_wager_unique 
    ON wagers (betId, userId, optionIndex)
  `);
}

const DEFAULT_STARTING_POINTS = 1000;

// Run the setup
setupDatabase();

// --- Database Functions ---
/**
 * Gets a user's point balance. Creates a record if one doesn't exist.
 * @param userId The user's Discord ID.
 * @returns The user's current point balance.
 */
export function getUserPoints(userId: string): number {
  let stmt = db.prepare('SELECT points FROM users WHERE userId = ?');
  let result = stmt.get(userId) as { points: number } | undefined;

  if (!result) {
    stmt = db.prepare('INSERT INTO users (userId, points) VALUES (?, ?)');
    stmt.run(userId, DEFAULT_STARTING_POINTS);
    return DEFAULT_STARTING_POINTS;
  }

  return result.points;
}

/**
 * Updates a user's point balance.
 * @param userId The user's Discord ID.
 * @param points The new point balance.
 */
export function setUserPoints(userId: string, points: number) {
  const stmt = db.prepare(`
    INSERT INTO users (userId, points) VALUES (?, ?)
    ON CONFLICT(userId) DO UPDATE SET points = excluded.points
  `);
  stmt.run(userId, points);
}

/**
 * Saves or updates a bet's main information in the 'bets' table.
 * @param bet The Bet instance to save.
 */
export function saveBet(bet: Bet) {
  const stmt = db.prepare(`
    INSERT INTO bets (id, creatorId, channelId, topic, options, endTime, isActive, winningOption)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET
      options = excluded.options,
      endTime = excluded.endTime,
      isActive = excluded.isActive,
      winningOption = excluded.winningOption
  `);

  stmt.run(
    bet.id,
    bet.creatorId,
    bet.channelId,
    bet.topic,
    JSON.stringify(bet.options), // Save the options array as a JSON string
    bet.endTime,
    bet.isActive ? 1 : 0,
    bet.winningOption
  );
}

export function getBet(betId: string): Bet | null {
  const betStmt = db.prepare('SELECT * FROM bets WHERE id = ?');
  const betRow = betStmt.get(betId) as any;

  if (!betRow) {
    return null;
  }

  // 1. Use our new static method to safely create the Bet object
  const bet = Bet.fromDbRow(betRow);

  // 2. Get all wagers for this bet
  const wagerStmt = db.prepare('SELECT userId, optionIndex, amount FROM wagers WHERE betId = ?');
  const wagers = wagerStmt.all(betId) as { userId: string, optionIndex: number, amount: number }[];

  bet.options.forEach(opt => {
    opt.pointsPool = 0;
    opt.bettorCount = 0;
    opt.maxBet = 0;
  });

  // Since each wager row is now unique per user, the logic is much simpler.
  for (const wager of wagers) {
    const option = bet.options[wager.optionIndex];
    if (option) {
      // Add to the total pool
      option.pointsPool += wager.amount;

      // Each row represents one unique bettor for this option
      option.bettorCount++;

      // Check for the highest total bet from a single user
      if (wager.amount > option.maxBet) {
        option.maxBet = wager.amount;
      }
    }
  }

  return bet;
}

/**
 * 處理使用者下注的完整交易流程。
 * 如果使用者已在該選項下注，則累加金額；否則，建立新的下注紀錄。
 * @returns 'success' | 'insufficient_points' | 'error'
 */
export function placeBetTransaction(userId: string, betId: string, optionIndex: number, amount: number): 'success' | 'insufficient_points' | 'error' {
  const userPoints = getUserPoints(userId);
  if (userPoints < amount) {
    return 'insufficient_points';
  }

  // 準備好所有需要執行的 SQL 語句
  const updateUserPointsStmt = db.prepare('UPDATE users SET points = ? WHERE userId = ?');
  const upsertWagerStmt = db.prepare(`
    INSERT INTO wagers (betId, userId, optionIndex, amount)
    VALUES (?, ?, ?, ?)
    ON CONFLICT(betId, userId, optionIndex) DO UPDATE SET
      amount = amount + excluded.amount
  `);

  const transaction = db.transaction(() => {
    updateUserPointsStmt.run(userPoints - amount, userId);
    upsertWagerStmt.run(betId, userId, optionIndex, amount);
  });

  try {
    transaction();
    return 'success';
  } catch (error) {
    console.error("Place bet transaction failed:", error);
    return 'error';
  }
}

/**
 * 取得特定賭注的所有下注紀錄。
 * @param betId 要查詢的賭注 ID。
 * @returns 一個包含所有下注紀錄的陣列。
 */
export function getWagersForBet(betId: string): { userId: string, optionIndex: number, amount: number }[] {
  const stmt = db.prepare('SELECT userId, optionIndex, amount FROM wagers WHERE betId = ?');
  return stmt.all(betId) as { userId: string, optionIndex: number, amount: number }[];
}

/**
 * 在單一交易中，為多位使用者更新點數（用於派彩）。
 * @param payouts 一個陣列，包含要更新的使用者 ID 和他們應得的點數。
 */
export function payoutWinnings(payouts: { userId: string, amount: number }[]) {
  const updateUserPoints = db.prepare('UPDATE users SET points = points + ? WHERE userId = ?');

  const transaction = db.transaction((payoutData) => {
    for (const payout of payoutData) {
      updateUserPoints.run(payout.amount, payout.userId);
    }
  });

  try {
    transaction(payouts);
  } catch (error) {
    console.error("Payout transaction failed:", error);
  }
}


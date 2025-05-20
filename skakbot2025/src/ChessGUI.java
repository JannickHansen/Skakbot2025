import Board.Board;
import Pieces.Bishop;
import Pieces.Knight;
import Pieces.King;
import Pieces.Pawn;
import Pieces.Piece;
import Pieces.Queen;
import Pieces.Rook;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public class ChessGUI {
    private JFrame frame;
    private JPanel cards;
    private final static String MENU = "Menu";
    private final static String GAME = "Board";

    private JButton[][] buttons = new JButton[8][8];
    private JPanel gamePanel;
    private Board boardModel;
    private boolean whiteTurn;
    private boolean gameOver;
    private int selectedRow, selectedCol;

    private Boolean humanIsWhite;
    private int iconSize = 64;
    private final Map<String, ImageIcon> iconCache = new HashMap<>();

    private int checkKingRow = -1, checkKingCol = -1;
    private ChessAI ai = new ChessAI();

    public ChessGUI() {
        SwingUtilities.invokeLater(this::createAndShowGUI);
    }

    private void createAndShowGUI() {
        frame = new JFrame("Skakspil");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(600, 600));
        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        frame.setLayout(new BorderLayout());

        cards = new JPanel(new CardLayout());
        cards.add(createMenuPanel(), MENU);
        gamePanel = createGamePanel();
        cards.add(gamePanel, GAME);

        frame.add(cards, BorderLayout.CENTER);
        showMenu();
        frame.setVisible(true);
    }

    private JPanel createMenuPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(new Color(40, 40, 40));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(20, 20, 20, 20);
        gbc.gridx = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("♚ VELKOMMEN TIL SKAK ♚", SwingConstants.CENTER);
        title.setFont(new Font("Serif", Font.BOLD, 30));
        title.setForeground(Color.WHITE);
        gbc.gridy = 0;
        panel.add(title, gbc);

        JButton playWhite = new JButton("Spil som Hvid");
        playWhite.setFont(new Font("SansSerif", Font.PLAIN, 20));
        playWhite.setBackground(new Color(220, 220, 220));
        playWhite.setFocusPainted(false);
        playWhite.addActionListener(e -> {
            humanIsWhite = true;
            startNewGame();
        });
        gbc.gridy = 1;
        panel.add(playWhite, gbc);

        JButton playBlack = new JButton("Spil som Sort");
        playBlack.setFont(new Font("SansSerif", Font.PLAIN, 20));
        playBlack.setBackground(new Color(220, 220, 220));
        playBlack.setFocusPainted(false);
        playBlack.addActionListener(e -> {
            humanIsWhite = false;
            startNewGame();
        });
        gbc.gridy = 2;
        panel.add(playBlack, gbc);

        return panel;
    }

    private JPanel createGamePanel() {
        JPanel panel = new JPanel(new GridLayout(8, 8));
        Color brown = new Color(139, 69, 19);
        Color tan = new Color(210, 180, 140);
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                JButton button = new JButton();
                button.setOpaque(true);
                button.setBorderPainted(true);
                button.setFocusPainted(false);
                button.setBackground((row + col) % 2 == 0 ? tan : brown);
                button.setHorizontalAlignment(SwingConstants.CENTER);
                button.setVerticalAlignment(SwingConstants.CENTER);
                final int r = row, c = col;
                button.addActionListener(e -> handleClick(r, c));
                buttons[row][col] = button;
                panel.add(button);
            }
        }
        return panel;
    }

    private void startNewGame() {
        boardModel = new Board();
        whiteTurn = true;
        gameOver = false;
        selectedRow = selectedCol = -1;
        iconSize = Math.min(gamePanel.getWidth(), gamePanel.getHeight()) / 8;
        iconCache.clear();
        checkKingRow = checkKingCol = -1;
        updateBoard();
        ((CardLayout) cards.getLayout()).show(cards, GAME);
        frame.setTitle("Skakspil – " + (whiteTurn ? "Hvids" : "Sorts") + " tur");
        if (humanIsWhite != null && !humanIsWhite) {
            triggerWhiteAIMove();
        }
    }

    private void showMenu() {
        ((CardLayout) cards.getLayout()).show(cards, MENU);
        frame.setTitle("Skakspil");
    }

    private void handleClick(int row, int col) {
        if (gameOver) return;
        clearHighlights();

        if (selectedRow < 0) {
            Piece clicked = boardModel.board[row][col];
            if (clicked != null && clicked.isWhite() == whiteTurn) {
                selectedRow = row;
                selectedCol = col;
                highlightMoves(row, col);
            }
            return;
        }

        Piece movingPiece = boardModel.board[selectedRow][selectedCol];
        if (movingPiece == null || movingPiece.isWhite() != whiteTurn) {
            selectedRow = selectedCol = -1;
            return;
        }

        boolean isCastling = movingPiece instanceof King
                && row == selectedRow
                && Math.abs(col - selectedCol) == 2;
        if (isCastling) {
            int direction = (col - selectedCol) > 0 ? 1 : -1;
            if (!boardModel.canCastle(whiteTurn, direction)) {
                buttons[row][col].setBorder(BorderFactory.createLineBorder(Color.RED, 3));
                selectedRow = selectedCol = -1;
                return;
            }
            boardModel.board[row][col] = movingPiece;
            boardModel.board[selectedRow][selectedCol] = null;
            movingPiece.move(row, col);

            int rookFrom = (direction == 1 ? 7 : 0);
            int rookTo   = selectedCol + direction;
            Piece rook   = boardModel.board[row][rookFrom];
            boardModel.board[row][rookTo]   = rook;
            boardModel.board[row][rookFrom] = null;
            rook.move(row, rookTo);

            whiteTurn = !whiteTurn;
            updateBoard();
            frame.setTitle("Skakspil – " + (whiteTurn ? "Hvids" : "Sorts") + " tur");
            selectedRow = selectedCol = -1;
            if (whiteTurn != humanIsWhite && !gameOver) {
                if (whiteTurn) triggerWhiteAIMove(); else triggerBlackAIMove();
            }
            return;
        }
        if (!movingPiece.isValidMove(row, col, boardModel.board)) {
            selectedRow = selectedCol = -1;
            return;
        }

        int origRow = selectedRow, origCol = selectedCol;
        Piece target = boardModel.board[row][col];
        boardModel.board[row][col] = movingPiece;
        boardModel.board[origRow][origCol] = null;
        movingPiece.setPosition(row, col);

        if (boardModel.isInCheck(whiteTurn)) {
            boardModel.board[origRow][origCol] = movingPiece;
            boardModel.board[row][col] = target;
            movingPiece.setPosition(origRow, origCol);
            buttons[row][col].setBorder(BorderFactory.createLineBorder(Color.RED, 3));
            selectedRow = selectedCol = -1;
            return;
        }

        movingPiece.move(row, col);
        if (movingPiece instanceof Pawn && (row == 0 || row == 7)) {
            promotePawn(row, col, movingPiece.isWhite());
        }

        whiteTurn = !whiteTurn;
        frame.setTitle("Skakspil – " + (whiteTurn ? "Hvids" : "Sorts") + " tur");
        updateSquare(origRow, origCol);
        updateSquare(row, col);

        if (boardModel.isInCheck(whiteTurn)) {
            handleCheckStatus(whiteTurn);
        } else {
            checkKingRow = checkKingCol = -1;
        }

        selectedRow = selectedCol = -1;
        if (whiteTurn != humanIsWhite && !gameOver) {
            if (whiteTurn) triggerWhiteAIMove(); else triggerBlackAIMove();
        }
    }

    private void highlightMoves(int fromRow, int fromCol) {
        Piece piece = boardModel.board[fromRow][fromCol];
        Border validHighlight   = BorderFactory.createLineBorder(Color.YELLOW, 3);
        Border invalidHighlight = BorderFactory.createLineBorder(Color.RED, 3);

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (!piece.isValidMove(r, c, boardModel.board)) continue;
                Piece target = boardModel.board[r][c];
                boardModel.board[fromRow][fromCol] = null;
                boardModel.board[r][c] = piece;
                piece.setPosition(r, c);
                boolean inCheck = boardModel.isInCheck(piece.isWhite());
                boardModel.board[fromRow][fromCol] = piece;
                boardModel.board[r][c] = target;
                piece.setPosition(fromRow, fromCol);
                buttons[r][c].setBorder(inCheck ? invalidHighlight : validHighlight);
            }
        }
        if (piece instanceof King) {
            boolean white = piece.isWhite();
            if (boardModel.canCastle(white, +1))
                buttons[fromRow][fromCol + 2].setBorder(validHighlight);
            if (boardModel.canCastle(white, -1))
                buttons[fromRow][fromCol - 2].setBorder(validHighlight);
        }
    }

    private void clearHighlights() {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                buttons[r][c].setBorder(null);
            }
        }
    }

    private void promotePawn(int row, int col, boolean isWhite) {
        String[] options = {"Dronning", "Tårn", "Løber", "Springer"};
        int choice = JOptionPane.showOptionDialog(
                frame,
                "Vælg brik til promotion:",
                "Bonde Promotion",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);

        Piece newPiece;
        switch (choice) {
            case 1: newPiece = new Rook(isWhite, row, col);   break;
            case 2: newPiece = new Bishop(isWhite, row, col); break;
            case 3: newPiece = new Knight(isWhite, row, col); break;
            default: newPiece = new Queen(isWhite, row, col); break;
        }
        boardModel.board[row][col] = newPiece;
        updateSquare(row, col);
    }

    private void handleCheckStatus(boolean currentPlayerWhite) {
        int kingR = -1, kingC = -1;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = boardModel.board[r][c];
                if (p instanceof King && p.isWhite() == currentPlayerWhite) {
                    kingR = r; kingC = c; break;
                }
            }
            if (kingR != -1) break;
        }
        checkKingRow = kingR;
        checkKingCol = kingC;
        buttons[kingR][kingC].setBorder(BorderFactory.createLineBorder(Color.RED, 4));

        if (boardModel.isCheckmate(currentPlayerWhite)) {
            buttons[kingR][kingC].setBorder(BorderFactory.createLineBorder(Color.RED, 6));
            gameOver = true;
            frame.setTitle("Skakspil – " + (currentPlayerWhite ? "Hvid" : "Sort") + " er skakmat");
            int resp = JOptionPane.showConfirmDialog(frame,
                    (currentPlayerWhite ? "Hvid" : "Sort") + " er skakmat!\nVil du vende tilbage til menuen?",
                    "Skakmat!", JOptionPane.YES_NO_OPTION);
            if (resp == JOptionPane.YES_OPTION) showMenu();
        }
    }

    public void triggerBlackAIMove() {
        ai.startSearchThread(boardModel, false, () -> {
            SwingUtilities.invokeLater(() -> {
                updateBoard();
                whiteTurn = true;
                frame.setTitle("Skakspil – Hvids tur");
                if (boardModel.isInCheck(true)) handleCheckStatus(true);
                else { checkKingRow = checkKingCol = -1; }
            });
        });
    }

    public void triggerWhiteAIMove() {
        ai.startSearchThread(boardModel, true, () -> {
            SwingUtilities.invokeLater(() -> {
                updateBoard();
                whiteTurn = false;
                frame.setTitle("Skakspil – Sorts tur");
                if (boardModel.isInCheck(false)) handleCheckStatus(false);
                else { checkKingRow = checkKingCol = -1; }
            });
        });
    }

    private void updateBoard() {
        int size = Math.min(gamePanel.getWidth(), gamePanel.getHeight()) / 8;
        if (size != iconSize) { iconSize = size; iconCache.clear(); }
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) updateSquare(r, c);
        gamePanel.revalidate();
        gamePanel.repaint();
    }

    private void updateSquare(int r, int c) {
        Piece piece = boardModel.board[r][c];
        JButton btn = buttons[r][c];
        if (piece != null) {
            String key = piece.getClass().getSimpleName() + "-" + piece.isWhite() + "-" + iconSize;
            ImageIcon scaledIcon = iconCache.get(key);
            if (scaledIcon == null) {
                ImageIcon raw = piece.getIcon();
                Image scaled = raw.getImage().getScaledInstance(iconSize, iconSize, Image.SCALE_SMOOTH);
                scaledIcon = new ImageIcon(scaled);
                iconCache.put(key, scaledIcon);
            }
            btn.setIcon(scaledIcon);
            btn.setText("");
        } else {
            btn.setIcon(null);
            btn.setText("");
        }
        if (r == checkKingRow && c == checkKingCol && !boardModel.isInCheck(whiteTurn)) btn.setBorder(null);
    }

    public static void main(String[] args) {
        new ChessGUI();
    }
}

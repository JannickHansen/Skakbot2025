import Pieces.Piece;
import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.Map;

public class ChessGUI {
    private JFrame frame;
    private JPanel cards;
    private final static String MENU = "Menu";
    private final static String GAME = "Game";

    private JButton[][] buttons = new JButton[8][8];
    private JPanel gamePanel;
    private Board boardModel;
    private boolean whiteTurn;
    private boolean gameOver;
    private int selectedRow, selectedCol;

    private int iconSize = 64;
    private final Map<String, ImageIcon> iconCache = new HashMap<>();

    // For highlighting check and checkmate
    private int checkKingRow = -1, checkKingCol = -1;

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

        JButton newGame = new JButton("Start Nyt Spil");
        newGame.setFont(new Font("SansSerif", Font.PLAIN, 20));
        newGame.setBackground(new Color(220, 220, 220));
        newGame.setFocusPainted(false);
        newGame.addActionListener(e -> startNewGame());
        gbc.gridy = 1;
        panel.add(newGame, gbc);

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
        frame.setTitle("Skakspil – Hvids tur");
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
        if (movingPiece == null
                || movingPiece.isWhite() != whiteTurn
                || !movingPiece.isValidMove(row, col, boardModel.board)) {
            selectedRow = selectedCol = -1;
            return;
        }

        int origRow = selectedRow, origCol = selectedCol;
        Piece targetPiece = boardModel.board[row][col];
        boardModel.board[row][col] = movingPiece;
        boardModel.board[origRow][origCol] = null;
        movingPiece.setPosition(row, col);

        // Fortryd ulovligt skak-træk
        if (boardModel.isInCheck(whiteTurn)) {
            boardModel.board[origRow][origCol] = movingPiece;
            boardModel.board[row][col] = targetPiece;
            movingPiece.setPosition(origRow, origCol);
            JOptionPane.showMessageDialog(frame,
                    "Du kan ikke efterlade din konge i skak!",
                    "Ugyldigt træk", JOptionPane.WARNING_MESSAGE);
            updateSquare(origRow, origCol);
            updateSquare(row, col);
            selectedRow = selectedCol = -1;
            return;
        }

        // Udfør træk
        whiteTurn = !whiteTurn;
        frame.setTitle("Skakspil – " + (whiteTurn ? "Hvids" : "Sorts") + " tur");
        updateSquare(origRow, origCol);
        updateSquare(row, col);

        // Check og checkmate
        if (boardModel.isInCheck(whiteTurn)) {
            // Find konge
            int kingR = -1, kingC = -1;
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    Piece p = boardModel.board[r][c];
                    if (p instanceof Pieces.King && p.isWhite() == whiteTurn) {
                        kingR = r; kingC = c;
                        break;
                    }
                }
                if (kingR != -1) break;
            }
            checkKingRow = kingR;
            checkKingCol = kingC;
            // Highlight check
            buttons[kingR][kingC].setBorder(BorderFactory.createLineBorder(Color.RED, 4));
            // Hvis checkmate, ekstra tyk og stop spil
            if (boardModel.isCheckmate(whiteTurn)) {
                buttons[kingR][kingC].setBorder(BorderFactory.createLineBorder(Color.RED, 6));
                gameOver = true;
                frame.setTitle("Skakspil – " + (whiteTurn ? "Hvid" : "Sort") + " er skakmat");
                // Vis dialog og vend tilbage til menu
                JOptionPane.showMessageDialog(frame,
                        (whiteTurn ? "Hvid" : "Sort") + " er skakmat!",
                        "Skakmat!", JOptionPane.INFORMATION_MESSAGE);
                int resp = JOptionPane.showConfirmDialog(frame,
                        "Vil du vende tilbage til menuen?",
                        "Skakmat!", JOptionPane.YES_NO_OPTION);
                if (resp == JOptionPane.YES_OPTION) {
                    showMenu();
                }
            }
        } else {
            checkKingRow = checkKingCol = -1;
        }

        selectedRow = selectedCol = -1;
    }

    private void updateBoard() {
        int size = Math.min(gamePanel.getWidth(), gamePanel.getHeight()) / 8;
        if (size != iconSize) {
            iconSize = size;
            iconCache.clear();
        }
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                updateSquare(r, c);
            }
        }
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
                Image scaled = raw.getImage()
                        .getScaledInstance(iconSize, iconSize, Image.SCALE_SMOOTH);
                scaledIcon = new ImageIcon(scaled);
                iconCache.put(key, scaledIcon);
            }
            btn.setIcon(scaledIcon);
            btn.setText("");
        } else {
            btn.setIcon(null);
            btn.setText("");
        }
        // Fjern highlight fra tidligere konge, hvis ikke i check længere
        if (r == checkKingRow && c == checkKingCol && !boardModel.isInCheck(whiteTurn)) {
            btn.setBorder(null);
        }
    }

    private void highlightMoves(int fromRow, int fromCol) {
        Border highlight = BorderFactory.createLineBorder(Color.YELLOW, 3);
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (boardModel.board[fromRow][fromCol].isValidMove(r, c, boardModel.board)) {
                    buttons[r][c].setBorder(highlight);
                }
            }
        }
    }

    private void clearHighlights() {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                // Behold rødt check/checkmate-ramme
                if (!gameOver && r == checkKingRow && c == checkKingCol && boardModel.isInCheck(whiteTurn)) continue;
                buttons[r][c].setBorder(null);
            }
        }
    }

    public static void main(String[] args) {
        new ChessGUI();
    }
}
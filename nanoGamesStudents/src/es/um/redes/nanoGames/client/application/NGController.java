package es.um.redes.nanoGames.client.application;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.Transaction;

import es.um.redes.nanoGames.broker.BrokerClient;
import es.um.redes.nanoGames.client.comm.NGGameClient;
import es.um.redes.nanoGames.client.shell.NGCommands;
import es.um.redes.nanoGames.client.shell.NGShell;
import es.um.redes.nanoGames.message.NGMensajeEntrarSala;
import es.um.redes.nanoGames.server.NGPlayerInfo;
import es.um.redes.nanoGames.utils.HibernateUtil;

// Clase encargada de coordinar la comunicación con el Broker y con el servidor de 
// juegos en función de la fase en la que nos encontremos o en función de lo que el usuario 
// introduzca a través del shell.

public class NGController {
	// Number of attempts to get a token
	private static final int MAX_NUMBER_OF_ATTEMPTS = 5;
	// Different states of the client (according to the automata)
	private static final byte PRE_TOKEN = 1;
	private static final byte PRE_REGISTRATION = 2;
	private static final byte OFF_ROOM = 3;
	private static final byte IN_ROOM = 4;
	// TODO Add additional states if necessary
	// The client for the broker
	private BrokerClient brokerClient;
	// The client for the game server
	private NGGameClient ngClient;
	// The shell for user commands from the standard input
	private NGShell shell;
	// Last command provided by the user
	private byte currentCommand;
	// Nickname of the user
	private String nickname;
	// Current room of the user (if any)
	private String room;
	// Current answer of the user (if any)
	private String answer;
	// Rules of the game
	private String rules = "";
	// Current status of the game
	private String gameStatus = "";
	// Token obtained from the broker
	private long token = 0;
	// Server hosting the games
	private String serverHostname;
	// Server socket;
	private DatagramSocket socket;
	// Solo se puede logear 1 vez.
	private boolean estaLogeado;

	public NGController(String brokerHostname, String serverHostname) {
		brokerClient = new BrokerClient(brokerHostname);
		shell = new NGShell();
		this.serverHostname = serverHostname;
		this.ngClient = new NGGameClient(serverHostname);
	}

	public byte getCurrentCommand() {
		return this.currentCommand;
	}

	public void setCurrentCommand(byte command) {
		currentCommand = command;
	}

	public void setCurrentCommandArguments(String[] args) {
		// According to the command we register the related parameters
		// We also check if the command is valid in the current state
		switch (currentCommand) {
		case NGCommands.COM_NICK:
			nickname = args[0];
			break;
		case NGCommands.COM_ENTER:
			room = args[0];
			break;
		case NGCommands.COM_ANSWER:
			answer = args[0];
			break;
		default:
		}
	}

	// Process commands provided by the users when they are not in a room
	public void processCommand(){
		switch (currentCommand) {
		case NGCommands.COM_TOKEN:
			getTokenAndDeliver();
			break;
		case NGCommands.COM_NICK:
			if (!estaLogeado) {
				registerNickName();
			}else {
				System.out.println("Ya estas logeado");
			}
			break;
		case NGCommands.COM_ROOMLIST:
			getRoomList();
			break;
		case NGCommands.COM_ENTER:
			enterTheGame();
			break;
		case NGCommands.COM_HELP:
			NGCommands.printCommandsHelp();
			break;
		case NGCommands.COM_STATUS:
			System.out.println("USUARIOS REGISTRADOS");
			List<NGPlayerInfo> listaRec = (List<NGPlayerInfo>) getAll();
			for (NGPlayerInfo ngPlayerInfo : listaRec) {
				System.out.println(ngPlayerInfo.toString());
			}
			break;
		case NGCommands.COM_QUIT:
			ngClient.disconnect();
			brokerClient.close();
			break;
		default:
		}
	}

	private void getRoomList() {
		ngClient.seeRoomList();
		
	}

	private void getAndShowRooms() {
		//Se hace automaticamente cuando hacemos ngClient.seeRoomList
	}

	private void registerNickName() {
		// We try to register the nick in the server (it will check for duplicates) 
		// TODO
		// We initialize the game client to be used to connect with the name server

		try {
			if (ngClient.registerNickname(nickname)) {
				System.out.println("El player " + nickname + " ha sido logeado de forma correcta.");
				estaLogeado = true;
			} else {
				System.out.println("El player " + nickname + " ya esta logeado, intentalo de nuevo con un nuevo nick.");
			}
		} catch (IOException e) {
			System.err.println("Nick no valido.");
		}

	}

	private void enterTheGame() {
		// The users request to enter in the room
		try {
			if (ngClient.sendJoinToRoom(room));
			else return;
		} catch (IOException e) {
			System.out.println("Unable to join to the room");
		}
		// If success, we change the state in order to accept new commands
		do {
			// We will only accept commands related to a room
			readGameCommandFromShell();
			processGameCommand();
		} while (currentCommand != NGCommands.COM_EXIT);
	}
	
	
	private void processGameCommand() {
		switch (currentCommand) {
		case NGCommands.COM_RULES:
			// TODO
			break;
		case NGCommands.COM_STATUS:
			// TODO
			break;
		case NGCommands.COM_ANSWER:
			sendAnswer();
			break;
		case NGCommands.COM_SOCKET_IN:
			// In this case the user did not provide a command but an incoming message was
			// received from the server
			processGameMessage();
			break;
		case NGCommands.COM_EXIT:
			// TODO
		}
	}

	private void exitTheGame() {
		// We notify the server that the user is leaving the game.
		// TODO
	}
	
	private void exitRoomGame() {
		// We notify the server that the user is leaving the room.
		// TODO
	}

	private void sendAnswer() {
		boolean condition = false;
		do {
			try {
				condition = ngClient.sendAnswer(answer);
			} catch (IOException e) {
				System.out.println("�Error en el envio de la respuesta!");
			}
		} while(condition);
	}

	private void processGameMessage() {
		// This method processes the incoming message received when the shell was
		// waiting for a user command
		// TODO
	}

	// Metodo para la obtencion del token por parte del cliente y el correspondiente
	// envio al servidor de juegos.
	private void getTokenAndDeliver() {

		// There will be a max number of attempts
		int attempts = MAX_NUMBER_OF_ATTEMPTS;
		int numIntentos = 0;

		// We try to obtain a token from the broker
		while (numIntentos < attempts && token == 0) {
			try {
				numIntentos++;
				token = brokerClient.getToken();
			} catch (IOException e1) {
				System.err.println("Error al obtener un token del Broker");
				token = 0;
			}
		}

		// If we have a token then we will send it to the game server
		if (token != 0) {
			try {
				// We initialize the game client to be used to connect with the name server
				// ngClient = new NGGameClient(serverHostname);
				// We send the token in order to verify it
				if (!ngClient.verifyToken(token, brokerClient)) {
					System.out.println("* The token is not valid.");
					token = 0;
				}
			} catch (IOException e) {
				System.out.println("* Check your connection, the game server is not available.");
				token = 0;
			}
		}
	}

	public void readGameCommandFromShell() {
		// We ask for a new game command to the Shell (and parameters if any)
		shell.readGameCommand(ngClient);
		setCurrentCommand(shell.getCommand());
		setCurrentCommandArguments(shell.getCommandArguments());
	}

	public void readGeneralCommandFromShell() {
		// We ask for a general command to the Shell (and parameters if any)
		shell.readGeneralCommand();
		setCurrentCommand(shell.getCommand());
		setCurrentCommandArguments(shell.getCommandArguments());
	}

	public boolean sendToken() {
		// We simulate that the Token is a command provided by the user in order to
		// reuse the existing code
		System.out.println("* Obtaining the token...");
		setCurrentCommand(NGCommands.COM_TOKEN);
		processCommand();
		if (token != 0) {
			System.out.println("* Token is " + token + " and it was validated by the server.");
		}
		return (token != 0);
	}

	public boolean shouldQuit() {
		return currentCommand == NGCommands.COM_QUIT;
	}

	// Metodos para el control de usuarios.

	// Añadir un player.
	public boolean create(Object obj) {
		System.out.println("LOGEAR");
		boolean nickValido = true;
		Session session = HibernateUtil.getSessionFactory().openSession();
		Transaction trans = null;

		try {
			trans = session.beginTransaction();
			session.save((NGPlayerInfo) obj);
			session.getTransaction().commit();
			System.out.println("NICK VALIDO1__: " + nickValido);
		} catch (RuntimeException e) {
			if (trans != null) {
				trans.rollback();
				nickValido = false;
				System.out.println("NICK VALIDO2__: " + nickValido);
				System.err.println("Nick no valido.");
			}
		} finally {
			session.close();
		}
		System.out.println("NICK VALIDO3__: " + nickValido);
		return nickValido;
	}

	// Eliminar un player.
	public static boolean delete(Object obj) {
		Session session = HibernateUtil.getSessionFactory().openSession();
		Transaction trans = null;

		try {
			trans = session.beginTransaction();
			session.delete((NGPlayerInfo) obj);
			session.getTransaction().commit();
			return true;

		} catch (RuntimeException e) {
			if (trans != null)
				trans.rollback();
			e.printStackTrace();
			return false;
		} finally {
			session.close();
		}
	}

	// Modificar un player.
	public static void update(Object obj) {
		Session session = HibernateUtil.getSessionFactory().openSession();
		Transaction trans = null;
		try {
			trans = session.beginTransaction();
			session.update((NGPlayerInfo) obj);
			session.getTransaction().commit();
		} catch (RuntimeException e) {
			if (trans != null) {
				trans.rollback();
			}
			e.printStackTrace();
		} finally {
			session.close();
		}

	}

	// Obtener un player
	public static Object get(int id) {
		Session session = HibernateUtil.getSessionFactory().openSession();
		Transaction trns = null;
		try {
			trns = session.beginTransaction();
			NGPlayerInfo ngp = session.get(NGPlayerInfo.class, id);
			session.getTransaction().commit();
			return ngp;
		} catch (RuntimeException e) {
			if (trns != null) {
				trns.rollback();
			}
			e.printStackTrace();
		} finally {
			session.close();
		}
		return null;
	}

	// Obtener todos los player.
	// TODO Se puede modificar la Query para obtener los player con mejor
	// puntuacion.
	@SuppressWarnings("unchecked")
	public static Object getAll() {
		List<NGPlayerInfo> listaRec = new ArrayList<NGPlayerInfo>();
		Session session = HibernateUtil.getSessionFactory().openSession();
		Transaction trans = null;
		try {
			trans = session.beginTransaction();
			listaRec = (ArrayList<NGPlayerInfo>) session.createQuery("FROM NGPlayerInfo").list();
			session.getTransaction().commit();
			return listaRec;
		} catch (RuntimeException e) {
			if (trans != null) {
				trans.rollback();
			}
			e.printStackTrace();
		} finally {
			session.close();
		}
		return null;
	}

}

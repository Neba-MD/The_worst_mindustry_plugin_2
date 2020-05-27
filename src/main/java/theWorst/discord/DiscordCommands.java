package theWorst.discord;

import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.event.message.MessageCreateEvent;
import org.javacord.api.listener.message.MessageCreateListener;
import theWorst.DiscordBot;

import java.awt.*;
import java.util.HashMap;



public class DiscordCommands implements MessageCreateListener {
    public HashMap<String,Command> commands = new HashMap<>();

    public void registerCommand(Command c){
        commands.put(c.name,c);
    }

    public boolean hasCommand(String command){
        return commands.containsKey(command);
    }

    public boolean isRestricted(String command){
        return commands.get(command) instanceof RoleRestrictedCommand;
    }

    @Override
    public void onMessageCreate(MessageCreateEvent messageCreateEvent) {

        String message = messageCreateEvent.getMessageContent();
        if(messageCreateEvent.getMessage().getAuthor().isBotUser()) return;
        if(!message.startsWith(DiscordBot.prefix)) return;
        if(DiscordBot.isInvalidChannel(messageCreateEvent)) return;
        int nameLength = message.indexOf(" ");
        if(nameLength<0){
            String name = message.substring(DiscordBot.prefix.length());
            runCommand(name,new CommandContext(messageCreateEvent, new String[0],""));
            return;
        }
        String theMessage = message.substring(nameLength+1);
        String[] args = theMessage.split(" ");
        String name = message.substring(DiscordBot.prefix.length(),nameLength);
        runCommand(name,new CommandContext(messageCreateEvent,args,theMessage));
    }

    private void runCommand(String name, CommandContext ctx) {
        Command command=commands.get(name);
        if(command==null) return;
        if(!command.hasPerm(ctx)){
            EmbedBuilder msg= new EmbedBuilder()
                    .setColor(Color.red)
                    .setTitle("ACCESS DENIED!")
                    .setDescription("You don't have high enough permission to use this command.");
            ctx.channel.sendMessage(msg);
        } else if(ctx.args.length<command.minArgs || ctx.args.length>command.maxArgs){
            EmbedBuilder msg= new EmbedBuilder()
                    .setColor(Color.red)
                    .setTitle(ctx.args.length<command.minArgs ? "TOO FEW ARGUMENTS!" : "TOO MATCH ARGUMENTS!")
                    .setDescription("Valid format : " + DiscordBot.prefix + name + " " + command.argStruct );
            ctx.channel.sendMessage(msg);
        } else {
            command.run(ctx);
        }

    }
}

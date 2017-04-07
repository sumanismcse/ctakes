package org.apache.ctakes.dictionary.creator.util;

import org.apache.ctakes.dictionary.creator.gui.umls.Concept;
import org.apache.ctakes.dictionary.creator.gui.umls.Tui;
import org.apache.ctakes.dictionary.creator.gui.umls.Vocabulary;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.ctakes.dictionary.creator.util.LambdaUtil.asSelf;
import static org.apache.ctakes.dictionary.creator.util.LambdaUtil.zeroLong;

/**
 * Author: SPF
 * Affiliation: CHIP-NLP
 * Date: 1/15/14
 */
final public class RareWordDbWriter {

   static private final Logger LOGGER = LogManager.getLogger( "RareWordDbWriter" );


   private RareWordDbWriter() {
   }

   private enum CuiTermsField {
      CUI( 1, Long.class ), RINDEX( 2, Integer.class ), TCOUNT( 3, Integer.class ),
      TEXT( 4, String.class ), RWORD( 5, String.class );
      final private int __index;
      final private Class __classType;

      CuiTermsField( final int index, final Class classType ) {
         __index = index;
         __classType = classType;
      }
   }



   static public boolean writeConcepts( final Map<Long, Concept> concepts,
                                     final String url, final String user, final String pass ) {
      // Get Count of appearance in dictionary per term token
      final Map<String, Integer> tokenCounts = RareWordUtil.getTokenCounts( concepts.values() );
      // For pmsdn tesseract user-words
//      try ( Writer rareWordWriter = new BufferedWriter( new FileWriter( "/tmp/ctakes-dictionary-gui/dictionary-temp/rarewords.txt" ) ) ) {
//         for ( Map.Entry<String,Integer> entry : tokenCounts.entrySet() ) {
//            if ( entry.getValue() > 24 && entry.getKey().length() > 4 && entry.getKey().length() < 15 ) {
//               boolean allAlpha = true;
//               for ( char c : entry.getKey().toCharArray() ) {
//                  if ( !Character.isLetter( c ) ) {
//                     allAlpha = false;
//                     break;
//                  }
//               }
//               if ( allAlpha ) {
//                  rareWordWriter.write( entry.getKey() + "\n" );
//               }
//            }
//         }
//      } catch ( IOException ioE ) {
//         LOGGER.error( ioE.getMessage() );
//      }
      // Create insert sql statements
      final String mainTableSql = JdbcUtil.createRowInsertSql( "CUI_TERMS", CuiTermsField.values() );
      final String tuiTableSql = JdbcUtil.createCodeInsertSql( "tui" );
      final String preftermTableSql = JdbcUtil.createCodeInsertSql( "prefterm" );
      final Map<String,String> insertCodeSqls = createCodeInsertSqls();

      long mainTableCount = 0;
      long tuiTableCount = 0;
      long preftermTableCount = 0;
      final Map<String,Long> codeTableCounts = createCodeCounts();
      final Connection connection = JdbcUtil.createDatabaseConnection( url, user, pass );
      try {
         // Create PreparedStatements from insert sql statements
         final PreparedStatement mainTableStatement = connection.prepareStatement( mainTableSql );
         final PreparedStatement tuiStatement = connection.prepareStatement( tuiTableSql );
         final PreparedStatement preftermStatement = connection.prepareStatement( preftermTableSql );
         final Map<String,PreparedStatement> codeStatements = createCodeStatements( connection, insertCodeSqls );

         for ( Map.Entry<Long, Concept> conceptEntry : concepts.entrySet() ) {
            final long cui = conceptEntry.getKey();
            final Concept concept = conceptEntry.getValue();
            // write main term table
            boolean conceptOk = false;
            for ( String text : conceptEntry.getValue().getTexts() ) {
               final RareWordUtil.IndexedRareWord indexedRareWord = RareWordUtil.getIndexedRareWord( text,
                                                                                                     tokenCounts );
               if ( RareWordUtil.NULL_RARE_WORD.equals( indexedRareWord ) ) {
                  continue;
               }
               conceptOk = true;
               mainTableStatement.setLong( CuiTermsField.CUI.__index, cui );
               mainTableStatement.setInt( CuiTermsField.RINDEX.__index, indexedRareWord.__index );
               mainTableStatement.setInt( CuiTermsField.TCOUNT.__index, indexedRareWord.__tokenCount );
               mainTableStatement.setString( CuiTermsField.TEXT.__index, text );
               mainTableStatement.setString( CuiTermsField.RWORD.__index, indexedRareWord.__word );
               mainTableStatement.executeUpdate();
               mainTableCount = incrementCount( "Main", mainTableCount );
            }
            if ( !conceptOk ) {
               continue;
            }
            // write tui table
            for ( Tui tui : concept.getTuis() ) {
               tuiStatement.setLong( CuiTermsField.CUI.__index, cui );
               tuiStatement.setInt( 2, tui.getIntValue() );
               tuiStatement.executeUpdate();
               tuiTableCount = incrementCount( "Tui", tuiTableCount );
            }
            // write preferred term table
            final String preferredText = concept.getPreferredText();
            if ( preferredText != null
                 && !preferredText.isEmpty()
                 && !preferredText.equals( Concept.PREFERRED_TERM_UNKNOWN ) ) {
               preftermStatement.setLong( CuiTermsField.CUI.__index, cui );
               preftermStatement.setString( 2, preferredText );
               preftermStatement.executeUpdate();
               preftermTableCount = incrementCount( "Preferred Term", preftermTableCount );
            }
            // write extra vocabulary code tables
            final Collection<String> vocabularies = concept.getVocabularies();
            for ( String vocabulary : vocabularies ) {
               final PreparedStatement statement = codeStatements.get( vocabulary.replace('-', '_') );
               statement.setLong( CuiTermsField.CUI.__index, cui );
               for ( String code : concept.getCodes( vocabulary ) ) {
                  setCodeAppropriately( statement, code, Vocabulary.getInstance().getVocabularyClass( vocabulary ) );
                  statement.executeUpdate();
                  codeTableCounts.put( vocabulary, incrementCount( vocabulary, codeTableCounts.get( vocabulary ) ) );
               }
            }
         }
         connection.commit();
         mainTableStatement.close();
         tuiStatement.close();
         preftermStatement.close();
         preftermStatement.close();
         for ( PreparedStatement codeStatement : codeStatements.values() ) {
            codeStatement.close();
         }
         final Statement writeDelayStatement = connection.createStatement();
         writeDelayStatement.execute( "SET WRITE_DELAY FALSE" );
         writeDelayStatement.close();
         final Statement setBinaryStatement = connection.createStatement();
         setBinaryStatement.execute( "SET SCRIPTFORMAT BINARY" );
         setBinaryStatement.close();
         final Statement readOnlyStatement = connection.createStatement();
         readOnlyStatement.execute( "SET READONLY TRUE" );
         readOnlyStatement.close();
         final Statement shutdownStatement = connection.createStatement();
         shutdownStatement.execute( "SHUTDOWN" );
         shutdownStatement.close();
         connection.commit();
         connection.close();
      } catch ( SQLException sqlE ) {
         LOGGER.error( sqlE.getMessage() );
         return false;
      }
      LOGGER.info( "Main Table Rows " + mainTableCount );
      LOGGER.info( "Tui Table Rows " + tuiTableCount );
      LOGGER.info( "Preferred Term Table Rows " + preftermTableCount );
      final Function<String,String> vocabCount = v -> v + " Table Rows " + codeTableCounts.get( v );
      Vocabulary.getInstance().getAllVocabularies().stream()
            .map( vocabCount )
            .forEach( LOGGER::info );
      return true;
   }


   static private Map<String,String> createCodeInsertSqls() {
      return Vocabulary.getInstance().getAllVocabularies().stream().map(vocab -> vocab.replace('-', '_'))
            .collect( Collectors.toMap( asSelf, JdbcUtil::createCodeInsertSql ) );
   }

   static private Map<String,PreparedStatement> createCodeStatements( final Connection connection,
                                                                      final Map<String,String> insertCodeSqls )
         throws SQLException {
      final Map<String,PreparedStatement> codeStatements = new HashMap<>( insertCodeSqls.size() );
      for ( Map.Entry<String,String> codeSql : insertCodeSqls.entrySet() ) {
         codeStatements.put( codeSql.getKey(), connection.prepareStatement( codeSql.getValue() ) );
      }
      return codeStatements;
   }

   static private Map<String,Long> createCodeCounts() {
      return Vocabulary.getInstance().getAllVocabularies().stream().collect( Collectors.toMap( asSelf, zeroLong ) );
   }

   static private void setCodeAppropriately( final PreparedStatement statement, final String code,
                                             final Class<?> type ) throws SQLException {
      if ( String.class.equals( type ) ) {
         statement.setString( 2, code );
      } else if ( Double.class.equals( type ) ) {
         statement.setDouble( 2, Double.valueOf( code ) );
      } else if ( Long.class.equals( type ) ) {
         statement.setLong( 2, Long.valueOf( code ) );
      } else if ( Integer.class.equals( type ) ) {
         statement.setInt( 2, Integer.valueOf( code ) );
      } else {
         LOGGER.error( "Could not set code for " + type.getName() );
         statement.setString( 2, code );
      }
   }

   static private long incrementCount( final String name, long count ) {
      count++;
      if ( count % 100000 == 0 ) {
         LOGGER.info( name + " Table Rows " + count );
      }
      return count;
   }

}